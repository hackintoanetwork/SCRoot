// SPDX-License-Identifier: GPL-2.0
/*
 * ksu_glue.c — Runtime KernelSU-Next front-end for SCR-01 (SM-H412J), kernel 4.14.186 arm64.
 *
 * WHY THIS FILE EXISTS
 *   KSU-Next's kernel core is built for the >=4.19 pt_regs syscall-wrapper ABI and drives its
 *   command channel through a kprobe on __arm64_sys_reboot + a sys_enter tracepoint dispatcher.
 *   On this device: CONFIG_KPROBES=n, CONFIG_KRETPROBES=n, CONFIG_FTRACE_SYSCALLS=n, and the
 *   syscall table holds CLASSIC-ABI entries SyS_foo(a0,a1,...) (pre-4.17 ABI). So the KSU-Next
 *   *transport* cannot run. This glue replaces it with direct sys_call_table overwrites through a
 *   vmap RW alias of the RO .rodata page (the proven ksuhook.c technique), calling KSU-Next core
 *   logic with plain C args instead of a pt_regs pointer.
 *
 *   This file is SELF-CONTAINED for Tier-1/Tier-2 bring-up (root via prctl, reboot-supercall fd,
 *   ioctl GRANT_ROOT/GET_INFO/manager, sucompat su-path redirect + escalation). Points marked
 *   "[KSU]" are where the genuine KSU-Next object files plug in once added to the build.
 *
 * DEVICE FACTS (device_symbols.txt / device.config): KASLR off, addresses stable.
 *   sys_call_table  VA 0xffffff8009101000  phys 0x41101000
 *   struct module .init offset +0x158 (upstream +0x150) -> run patch_init_off.py on the .ko
 *   selinux enforcing var 0xffffff8009e4ce40 ; AVC neuter site avc_has_perm_noaudit+0x78=0x4045c998
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/cred.h>
#include <linux/capability.h>
#include <linux/vmalloc.h>
#include <linux/mm.h>
#include <linux/fs.h>
#include <linux/file.h>
#include <linux/fdtable.h>
#include <linux/ptrace.h>
#include <linux/anon_inodes.h>
#include <linux/namei.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/poll.h>
#include <linux/wait.h>
#include <linux/ktime.h>
#include <linux/un.h>
#include <linux/mount.h>
#include <linux/nsproxy.h>
#include <linux/syscalls.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>   /* for_each_thread */
#include <linux/thread_info.h>    /* TIF_SECCOMP, clear_tsk_thread_flag */
#include <linux/rcupdate.h>
#include <linux/umh.h>
#include <asm/unistd.h>
#include <asm/cacheflush.h>
#include <asm/memory.h>
#include <asm/ptrace.h>
#include <asm/uaccess.h>

/* ------------------------------------------------------------------ *
 * 0. Device addresses / knobs — all overridable at insmod time.
 * ------------------------------------------------------------------ */
static unsigned long sct_va   = 0xffffff8009101000UL;  /* sys_call_table virtual   */
static unsigned long sct_phys = 0x41101000UL;          /* sys_call_table physical  */
static unsigned long kln_addr = 0xffffff800817a8f0UL;  /* kallsyms_lookup_name VA  */
static unsigned long secctx2secid_addr;                /* optional: 0 -> resolve via kln */
static bool selinux_domain_switch = true;              /* false -> rely on global AVC neuter */
module_param(sct_va,   ulong, 0444);
module_param(sct_phys, ulong, 0444);
module_param(kln_addr, ulong, 0444);
module_param(secctx2secid_addr, ulong, 0444);
module_param(selinux_domain_switch, bool, 0644);


/* ---- Samsung task_struct/cred offsets (differ from upstream headers) ----
 * From exploit RE (RE/10): task_struct->cred at +1936, cred->uid at +4, euid at +20.
 * `current` (SP_EL0) is arch-correct; only struct-member offsets differ, so read manually. */
static unsigned long task_cred_off = 1936;   /* task_struct->cred */
static unsigned long cred_uid_off  = 4;      /* cred->uid  */
static unsigned long cred_euid_off = 20;     /* cred->euid */
/*
 * Prepared 4.14 headers place task_struct->cred at 0x730 and ->seccomp at
 * 0x7d8. Samsung inserts 0x60 bytes before cred (runtime cred=0x790), so the
 * same measured layout delta places seccomp at 0x838.
 */
static unsigned long task_seccomp_off = 0x838;
static bool enable_sucompat = false;         /* Tier2: su-path redirect (needs pt_regs) */
static bool enable_manager_fd = true;        /* seed [ksu_driver] fd into zygote for the manager */
static bool enable_dev_prctl;                /* development hot-swap only; OFF in release */
static bool userspace_ready;                 /* ksud late-load stages completed this boot */
static uint bootstrap_appid = (uint)-1;      /* signed RootApp; set dynamically by the APK */
static unsigned long stat_grant_root;
static unsigned long stat_manager_exec_fd;
static unsigned long stat_root_exec_fd;
static unsigned long stat_root_domain_refresh;
static unsigned long stat_manager_child_seccomp;
static unsigned long stat_seccomp_clear;
static unsigned long stat_seccomp_mode_clear;
static unsigned long stat_seccomp_mode_bad;
static unsigned long stat_seccomp_bypass;
static unsigned long stat_prctl_bypass;
static unsigned long stat_driver_fd_install;
static unsigned long stat_driver_fd_reuse;
static unsigned long stat_driver_fd_fail;
static unsigned long stat_execveat_empty_root;
static unsigned long stat_profile_grant;
static unsigned long stat_profile_persist;
static unsigned long stat_profile_load;
static unsigned long stat_profile_error;
static unsigned long stat_profile_context_fallback;
static unsigned long stat_sepolicy_compat;
static unsigned long stat_kernel_umount;
static unsigned long stat_kernel_umount_fail;
static unsigned long stat_adb_root_block;
static unsigned long stat_adb_root_env;
static unsigned long stat_adb_root_env_fail;
static unsigned long stat_adb_root_recover;
static unsigned long stat_adb_root_last_sp;
static unsigned long stat_adb_root_last_envp;
static long stat_adb_root_last_syscall;
static unsigned long stat_adb_restart;
static unsigned long stat_adb_restart_fail;
static unsigned long stat_sulog_emit;
static unsigned long stat_sulog_drop;
static unsigned long stat_init_pgrp_compat;
static unsigned long stat_init_pgrp_fail;
static long (*orig_prctl)(long, long, long, long, long);
static long (*orig_setpgid)(pid_t, pid_t);
static long (*orig_renameat2)(int, const char __user *, int,
			      const char __user *, unsigned int);
module_param(task_cred_off, ulong, 0444);
module_param(cred_uid_off,  ulong, 0444);
module_param(cred_euid_off, ulong, 0444);
module_param(task_seccomp_off, ulong, 0444);
module_param(enable_sucompat, bool, 0644);
module_param(enable_manager_fd, bool, 0644);
module_param(enable_dev_prctl, bool, 0600);
module_param(userspace_ready, bool, 0644);
module_param(bootstrap_appid, uint, 0444);
module_param(stat_grant_root, ulong, 0444);
module_param(stat_manager_exec_fd, ulong, 0444);
module_param(stat_root_exec_fd, ulong, 0444);
module_param(stat_root_domain_refresh, ulong, 0444);
module_param(stat_manager_child_seccomp, ulong, 0444);
module_param(stat_seccomp_clear, ulong, 0444);
module_param(stat_seccomp_mode_clear, ulong, 0444);
module_param(stat_seccomp_mode_bad, ulong, 0444);
module_param(stat_seccomp_bypass, ulong, 0444);
module_param(stat_prctl_bypass, ulong, 0444);
module_param(stat_driver_fd_install, ulong, 0444);
module_param(stat_driver_fd_reuse, ulong, 0444);
module_param(stat_driver_fd_fail, ulong, 0444);
module_param(stat_execveat_empty_root, ulong, 0444);
module_param(stat_profile_grant, ulong, 0444);
module_param(stat_profile_persist, ulong, 0444);
module_param(stat_profile_load, ulong, 0444);
module_param(stat_profile_error, ulong, 0444);
module_param(stat_profile_context_fallback, ulong, 0444);
module_param(stat_sepolicy_compat, ulong, 0444);
module_param(stat_kernel_umount, ulong, 0444);
module_param(stat_kernel_umount_fail, ulong, 0444);
module_param(stat_adb_root_block, ulong, 0444);
module_param(stat_adb_root_env, ulong, 0444);
module_param(stat_adb_root_env_fail, ulong, 0444);
module_param(stat_adb_root_recover, ulong, 0444);
module_param(stat_adb_root_last_sp, ulong, 0444);
module_param(stat_adb_root_last_envp, ulong, 0444);
module_param(stat_adb_root_last_syscall, long, 0444);
module_param(stat_adb_restart, ulong, 0444);
module_param(stat_adb_restart_fail, ulong, 0444);
module_param(stat_sulog_emit, ulong, 0444);
module_param(stat_sulog_drop, ulong, 0444);
module_param(stat_init_pgrp_compat, ulong, 0444);
module_param(stat_init_pgrp_fail, ulong, 0444);

static inline const void *dev_cred(void)
{ return *(const void * const *)((const char *)current + task_cred_off); }
static inline uid_t dev_uid(void)
{ const void *c = dev_cred(); return c ? *(const uid_t *)((const char *)c + cred_uid_off) : (uid_t)-1; }
static inline uid_t dev_euid(void)
{ const void *c = dev_cred(); return c ? *(const uid_t *)((const char *)c + cred_euid_off) : (uid_t)-1; }
static inline gid_t dev_gid(void)
{ const void *c = dev_cred(); return c ? *(const gid_t *)((const char *)c + cred_uid_off + 4) : (gid_t)-1; }

/* arm64 asm-generic syscall numbers on 4.14 (no faccessat2, no bare stat/access) */
#define NR_REBOOT     142
#define NR_FCNTL       25
#define NR_FACCESSAT   48
#define NR_NEWFSTATAT  79
#define NR_UMOUNT      39
#define NR_MOUNT       40
#define NR_CLOSE       57
#define NR_UNSHARE     97
#define NR_SETNS      268
#define NR_SETGID     144
#define NR_SETUID     146
#define NR_SETRESUID  147   /* manager-fd install trigger (zygote app specialization) */
#define NR_SETRESGID  149
#define NR_SETPGID    154
#define NR_SETGROUPS  159
#define NR_GETPID     172
#define NR_GETPPID    173
#define NR_GETTID     178
#define NR_PRCTL      167
#define NR_EXECVE     221
#define NR_EXECVEAT   281
#define NR_SECCOMP    277   /* seccomp(2): block re-arm in su-spawned root helpers */
#define NR_RENAMEAT2  276   /* atomic App Profile allowlist replacement */
#define NR_CONNECT    203   /* property-service handoff for Manager ADB restart */
#define NR_STATX      291   /* optional extra su-probe path */
#define PR_SET_SECCOMP_OPT 22
#define PR_SET_NO_NEW_PRIVS_OPT 38
#define PR_GET_NO_NEW_PRIVS_OPT 39
#define FLAG_KSU_NO_NEW_PRIVS (1ULL << 0)

/* ------------------------------------------------------------------ *
 * 1. Protocol constants (from ksu_proto.h / uapi/supercall.h)
 * ------------------------------------------------------------------ */
#define KSU_INSTALL_MAGIC1   0xDEADBEEFU
#define KSU_INSTALL_MAGIC2   0xCAFEBABEU
#define CHANGE_MANAGER_UID   10006U

#define KSU_IOCTL_GRANT_ROOT        0x00004B01U
#define KSU_IOCTL_GET_INFO          0x80104B02U
#define KSU_IOCTL_GET_INFO_LEGACY   0x80004B02U
#define KSU_IOCTL_REPORT_EVENT      0x40004B03U
#define KSU_IOCTL_SET_SEPOLICY      0xC0004B04U
#define KSU_IOCTL_CHECK_SAFEMODE    0x80004B05U
#define KSU_IOCTL_GET_MANAGER_APPID 0x80004B0AU
#define KSU_IOCTL_UID_SHOULD_UMOUNT 0xC0004B09U
#define KSU_IOCTL_GET_APP_PROFILE   0xC0004B0BU
#define KSU_IOCTL_SET_APP_PROFILE   0x40004B0CU  /* complete v4 App Profile wire structure */
#define KSU_IOCTL_UID_GRANTED_ROOT  0xC0004B08U
#define KSU_IOCTL_GET_FEATURE       0xC0004B0DU  /* _IOC(RW,'K',13,0): query feature state */
#define KSU_IOCTL_SET_FEATURE       0x40004B0EU
#define KSU_IOCTL_GET_WRAPPER_FD    0x40004B0FU
#define KSU_IOCTL_MANAGE_MARK       0xC0004B10U
#define KSU_IOCTL_NUKE_EXT4_SYSFS   0x40004B11U
#define KSU_IOCTL_ADD_TRY_UMOUNT    0x40004B12U
#define KSU_IOCTL_SET_INIT_PGRP     0x00004B13U
#define KSU_IOCTL_GET_SULOG_FD      0x40044B14U
#define KSU_IOCTL_DISABLE_ESCAPE    0x00004B15U
#define KSU_IOCTL_GET_HOOK_MODE     0x80004B62U  /* _IOC(R, 'K',98,0): hook backend name    */
#define KSU_IOCTL_GET_VERSION_TAG   0x80004B63U  /* _IOC(R, 'K',99,0): version tag string   */
#define KSU_FEATURE_SU_COMPAT           0u
#define KSU_FEATURE_KERNEL_UMOUNT       1u
#define KSU_FEATURE_SULOG               2u
#define KSU_FEATURE_ADB_ROOT            3u
#define KSU_FEATURE_SELINUX_HIDE        4u
#define KSU_FEATURE_AVC_SPOOF       10003u

#define KERNEL_SU_VERSION       33214u
#define KERNEL_SU_UAPI_VERSION      2u
#define KSU_FEATURE_MAX         10004u
#define KSU_PER_USER_RANGE     100000u
#define KSU_SEPOLICY_MAX_BYTES (1024u * 1024u)

struct ksu_set_sepolicy_cmd {
	__u64 data_len;
	__aligned_u64 data;
};

#define KSU_APP_PROFILE_VER 4
#define KSU_MAX_PACKAGE_NAME 256
#define KSU_MAX_GROUPS 32
#define KSU_SELINUX_DOMAIN 64
struct ksu_root_profile {
	__s32 uid, gid;
	__u32 groups_count;
	__s32 groups[KSU_MAX_GROUPS];
	struct { __u64 effective, permitted, inheritable; } capabilities;
	char selinux_domain[KSU_SELINUX_DOMAIN];
	__s32 namespaces;
	__u64 flags;
};
struct ksu_non_root_profile { bool umount_modules; };
struct ksu_app_profile {
	__u32 version;
	char key[KSU_MAX_PACKAGE_NAME];
	__s32 curr_uid;
	bool allow_su;
	union {
		struct {
			bool use_default;
			char template_name[KSU_MAX_PACKAGE_NAME];
			struct ksu_root_profile profile;
		} rp_config;
		struct {
			bool use_default;
			struct ksu_non_root_profile profile;
		} nrp_config;
	};
};
struct ksu_get_info_cmd { __u32 version, flags, features, uapi_version; };
struct ksu_get_info_legacy_cmd { __u32 version, flags, features; };
struct ksu_uid_cmd { __u32 uid; __u8 granted; };
struct ksu_get_feature_cmd { __u32 feature_id; __u64 value; __u8 supported; };
struct ksu_set_feature_cmd { __u32 feature_id; __u64 value; };
struct ksu_hook_mode_cmd { char mode[16]; };
struct ksu_version_tag_cmd { char tag[32]; };
struct ksu_allow_list_hdr { __u16 count; __u16 total_count; };  /* followed by __u32 uids[] */

#define KSU_IOCTL_NEW_GET_ALLOW_LIST 0xC0044B06U  /* _IOWR('K',6,struct{u16,u16}) */
#define KSU_IOCTL_NEW_GET_DENY_LIST  0xC0044B07U  /* _IOWR('K',7,...)             */
#define KSU_IOCTL_GET_ALLOW_LIST     0xC0004B06U
#define KSU_IOCTL_GET_DENY_LIST      0xC0004B07U

#define KSU_MARK_GET      1u
#define KSU_MARK_MARK     2u
#define KSU_MARK_UNMARK   3u
#define KSU_MARK_REFRESH  4u
struct ksu_manage_mark_cmd { __u32 operation; __s32 pid; __u32 result; };
struct ksu_add_try_umount_cmd {
	__u64 arg;
	__u32 flags;
	__u8 mode;
};
struct ksu_get_sulog_fd_cmd { __u32 flags; };
#define KSU_UMOUNT_WIPE     0u
#define KSU_UMOUNT_ADD      1u
#define KSU_UMOUNT_DEL      2u
#define KSU_UMOUNT_GETSIZE 107u
#define KSU_UMOUNT_GETLIST 108u

struct ksu_legacy_list_cmd {
	__u32 uids[128];
	__u32 count;
	__u8 allow;
};

/* ------------------------------------------------------------------ *
 * 2. Manager identity + allowlist  ([KSU] replace with manager_identity.h + allowlist.c)
 * ------------------------------------------------------------------ */
static uid_t ksu_manager_appid = (uid_t)-1;
/*
 * The first userspace bootstrap still carries Android's inherited app seccomp
 * filter, which intentionally blocks reboot(2).  Expose a root-writable,
 * device-local crown path so RootApp never needs the reboot supercall here.
 */
module_param_named(manager_appid, ksu_manager_appid, uint, 0644);
static inline bool is_manager(void)
{ return ksu_manager_appid == (dev_uid() % KSU_PER_USER_RANGE); }
static void ksu_set_manager_appid(uid_t appid) { ksu_manager_appid = appid; }

static DEFINE_SPINLOCK(allow_lock);
static uid_t allow_uids[256];
static int   allow_n;
static bool ksu_is_allow_uid(uid_t uid)
{
	int i; bool r = false; unsigned long f;
	spin_lock_irqsave(&allow_lock, f);
	for (i = 0; i < allow_n; i++) if (allow_uids[i] == uid) { r = true; break; }
	spin_unlock_irqrestore(&allow_lock, f);
	return r;
}
static void ksu_allow_set(uid_t uid, bool allow)
{
	unsigned long f; int i;
	spin_lock_irqsave(&allow_lock, f);
	for (i = 0; i < allow_n; i++) {
		if (allow_uids[i] != uid)
			continue;
		if (!allow) {
			allow_uids[i] = allow_uids[--allow_n];
		}
		goto out;
	}
	if (allow && allow_n < ARRAY_SIZE(allow_uids))
		allow_uids[allow_n++] = uid;
out:
	spin_unlock_irqrestore(&allow_lock, f);
}

/*
 * Keep the complete v4 profile, not only allow_su.  The on-disk layout is the
 * official KernelSU v4 .allowlist format, so the manager/ksud can migrate this
 * port to a source-integrated kernel later without losing policy.
 */
#define KSU_MAX_PROFILES 256
#define KSU_PROFILE_FILE_MAGIC 0x7f4b5355U
#define KSU_PROFILE_FILE_VERSION 4U
#define KSU_PROFILE_PRESERVE_UID 9999
#define KSU_PROFILE_FILE "/data/adb/ksu/.allowlist"
#define KSU_PROFILE_TMP_FILE "/data/adb/ksu/.allowlist.new"
struct ksu_profile_slot {
	bool used;
	struct ksu_app_profile profile;
};
static DEFINE_MUTEX(profile_lock);
/* Serialize RAM mutation + durable replacement as one profile transaction. */
static DEFINE_MUTEX(profile_tx_lock);
static struct ksu_profile_slot profile_slots[KSU_MAX_PROFILES];
static bool default_nonroot_umount = true;
static const struct cred *storage_cred;
static int (*p_vfs_fsync)(struct file *, int);

static int profile_find_locked(uid_t uid)
{
	int i;
	for (i = 0; i < KSU_MAX_PROFILES; i++)
		if (profile_slots[i].used &&
		    profile_slots[i].profile.curr_uid == (int)uid)
			return i;
	return -1;
}

static bool profile_valid(const struct ksu_app_profile *p)
{
	u64 cap_mask = (1ULL << (CAP_LAST_CAP + 1)) - 1;

	if (!p || p->version != KSU_APP_PROFILE_VER || p->curr_uid < 0)
		return false;
	if (strnlen(p->key, sizeof(p->key)) >= sizeof(p->key))
		return false;
	if (p->allow_su &&
	    strnlen(p->rp_config.template_name,
		    sizeof(p->rp_config.template_name)) >=
		    sizeof(p->rp_config.template_name))
		return false;
	if (p->allow_su && !p->rp_config.use_default) {
		unsigned int i;
		const struct ksu_root_profile *rp = &p->rp_config.profile;
		if (rp->uid < 0 || rp->gid < 0)
			return false;
		if (p->rp_config.profile.groups_count > KSU_MAX_GROUPS)
			return false;
		for (i = 0; i < rp->groups_count; i++)
			if (rp->groups[i] < 0)
				return false;
		if ((rp->capabilities.effective |
		     rp->capabilities.permitted |
		     rp->capabilities.inheritable) & ~cap_mask)
			return false;
		if (strnlen(p->rp_config.profile.selinux_domain,
			    sizeof(p->rp_config.profile.selinux_domain)) == 0 ||
		    strnlen(p->rp_config.profile.selinux_domain,
			    sizeof(p->rp_config.profile.selinux_domain)) >=
			    sizeof(p->rp_config.profile.selinux_domain))
			return false;
		if (p->rp_config.profile.namespaces < 0 ||
		    p->rp_config.profile.namespaces > 2)
			return false;
		if (rp->flags & ~FLAG_KSU_NO_NEW_PRIVS)
			return false;
	}
	if (p->curr_uid == KSU_PROFILE_PRESERVE_UID &&
	    strcmp(p->key, "$"))
		return false;
	return true;
}

static void profile_default_root(struct ksu_root_profile *rp)
{
	memset(rp, 0, sizeof(*rp));
	rp->uid = 0;
	rp->gid = 0;
	rp->groups_count = 1;
	rp->groups[0] = 0;
	rp->capabilities.effective = (1ULL << (CAP_LAST_CAP + 1)) - 1;
	rp->capabilities.permitted = rp->capabilities.effective;
	rp->capabilities.inheritable = rp->capabilities.effective;
	strlcpy(rp->selinux_domain, "u:r:shell:s0",
		sizeof(rp->selinux_domain));
	rp->namespaces = 0; /* inherited */
	rp->flags = 0;
}

static int profile_get(uid_t uid, struct ksu_app_profile *out)
{
	int idx;
	mutex_lock(&profile_lock);
	idx = profile_find_locked(uid);
	if (idx >= 0 && out)
		memcpy(out, &profile_slots[idx].profile, sizeof(*out));
	mutex_unlock(&profile_lock);
	return idx < 0 ? -ENOENT : 0;
}

static int profile_set_memory(const struct ksu_app_profile *p)
{
	struct ksu_app_profile normalized;
	int idx, free_idx = -1, i;
	if (!profile_valid(p))
		return -EINVAL;
	memcpy(&normalized, p, sizeof(normalized));
	if (normalized.allow_su && !normalized.rp_config.use_default) {
		u64 effective = normalized.rp_config.profile.capabilities.effective;

		/*
		 * KernelSU-Next Manager v3.3 serializes the UI capability list into
		 * `effective` only.  Upstream then derives permitted and bounding from
		 * that field.  Requiring the unused `permitted` wire field made every
		 * capability-bearing template fail SET_APP_PROFILE with EINVAL.
		 */
		normalized.rp_config.profile.capabilities.permitted = effective;
		normalized.rp_config.profile.capabilities.inheritable &= effective;
	}

	mutex_lock(&profile_lock);
	idx = profile_find_locked(normalized.curr_uid);
	if (idx < 0) {
		for (i = 0; i < KSU_MAX_PROFILES; i++) {
			if (!profile_slots[i].used) {
				free_idx = i;
				break;
			}
		}
		idx = free_idx;
	}
	if (idx < 0) {
		mutex_unlock(&profile_lock);
		return -E2BIG;
	}
	profile_slots[idx].used = true;
	memcpy(&profile_slots[idx].profile, &normalized, sizeof(normalized));
	if (normalized.curr_uid == KSU_PROFILE_PRESERVE_UID &&
	    !normalized.allow_su)
		default_nonroot_umount = normalized.nrp_config.use_default ?
			true : normalized.nrp_config.profile.umount_modules;
	mutex_unlock(&profile_lock);
	ksu_allow_set(normalized.curr_uid, normalized.allow_su);
	return 0;
}

static void profile_remove_memory(uid_t uid)
{
	int idx;

	mutex_lock(&profile_lock);
	idx = profile_find_locked(uid);
	if (idx >= 0) {
		memset(&profile_slots[idx], 0, sizeof(profile_slots[idx]));
		if (uid == KSU_PROFILE_PRESERVE_UID)
			default_nonroot_umount = true;
	}
	mutex_unlock(&profile_lock);
	ksu_allow_set(uid, false);
}

static int profile_persist(void)
{
	const struct cred *old;
	struct file *fp;
	mm_segment_t oldfs;
	loff_t off = 0;
	u32 magic = KSU_PROFILE_FILE_MAGIC;
	u32 version = KSU_PROFILE_FILE_VERSION;
	int i, rc = 0;

	if (!storage_cred)
		return -EAGAIN;
	old = override_creds(storage_cred);
	fp = filp_open(KSU_PROFILE_TMP_FILE,
		       O_WRONLY | O_CREAT | O_TRUNC, 0600);
	if (IS_ERR(fp)) {
		rc = PTR_ERR(fp);
		goto out_cred;
	}
	if (kernel_write(fp, (char *)&magic, sizeof(magic), &off) != sizeof(magic) ||
	    kernel_write(fp, (char *)&version, sizeof(version), &off) != sizeof(version)) {
		rc = -EIO;
		goto out_close;
	}

	mutex_lock(&profile_lock);
	for (i = 0; i < KSU_MAX_PROFILES; i++) {
		if (!profile_slots[i].used)
			continue;
		if (kernel_write(fp, (char *)&profile_slots[i].profile,
				 sizeof(profile_slots[i].profile), &off) !=
		    sizeof(profile_slots[i].profile)) {
			rc = -EIO;
			break;
		}
	}
	mutex_unlock(&profile_lock);
	if (!rc && p_vfs_fsync)
		rc = p_vfs_fsync(fp, 0);
out_close:
	filp_close(fp, NULL);
	if (!rc) {
		if (!orig_renameat2) {
			rc = -EOPNOTSUPP;
		} else {
			oldfs = get_fs();
			set_fs(KERNEL_DS);
			rc = orig_renameat2(AT_FDCWD,
				(const char __user *)KSU_PROFILE_TMP_FILE,
				AT_FDCWD, (const char __user *)KSU_PROFILE_FILE, 0);
			set_fs(oldfs);
		}
	}
out_cred:
	revert_creds(old);
	if (rc)
		stat_profile_error++;
	else
		stat_profile_persist++;
	return rc;
}

static void profile_load(void)
{
	const struct cred *old;
	struct file *fp;
	struct ksu_app_profile *loaded;
	struct ksu_app_profile p;
	loff_t off = 0;
	u32 magic = 0, version = 0;
	ssize_t n;
	int count = 0, i, j;
	bool valid_file = false;

	if (!storage_cred)
		return;
	loaded = kcalloc(KSU_MAX_PROFILES, sizeof(*loaded), GFP_KERNEL);
	if (!loaded) {
		stat_profile_error++;
		return;
	}
	old = override_creds(storage_cred);
	fp = filp_open(KSU_PROFILE_FILE, O_RDONLY, 0);
	if (IS_ERR(fp))
		goto out_revert;
	if (kernel_read(fp, (char *)&magic, sizeof(magic), &off) != sizeof(magic) ||
	    kernel_read(fp, (char *)&version, sizeof(version), &off) != sizeof(version) ||
	    magic != KSU_PROFILE_FILE_MAGIC || version != KSU_PROFILE_FILE_VERSION) {
		stat_profile_error++;
		goto out_close;
	}
	for (;;) {
		memset(&p, 0, sizeof(p));
		n = kernel_read(fp, (char *)&p, sizeof(p), &off);
		if (n == 0) {
			valid_file = true;
			break;
		}
		if (n != sizeof(p) || count >= KSU_MAX_PROFILES ||
		    !profile_valid(&p)) {
			stat_profile_error++;
			break;
		}
		for (j = 0; j < count; j++)
			if (loaded[j].curr_uid == p.curr_uid) {
				stat_profile_error++;
				goto out_close;
			}
		memcpy(&loaded[count++], &p, sizeof(p));
	}
out_close:
	filp_close(fp, NULL);
out_revert:
	revert_creds(old);
	if (valid_file) {
		for (i = 0; i < count; i++) {
			if (profile_set_memory(&loaded[i])) {
				stat_profile_error++;
				for (j = 0; j < i; j++)
					profile_remove_memory(loaded[j].curr_uid);
				break;
			}
			stat_profile_load++;
		}
	}
	kfree(loaded);
}

static bool profile_should_umount(uid_t uid)
{
	struct ksu_app_profile p;
	bool result;

	if ((uid % KSU_PER_USER_RANGE) == ksu_manager_appid ||
	    ksu_is_allow_uid(uid))
		return false;
	if (profile_get(uid, &p))
		return default_nonroot_umount;
	if (p.allow_su)
		return false;
	result = p.nrp_config.use_default ? default_nonroot_umount :
		p.nrp_config.profile.umount_modules;
	return result;
}

/* GRANT_ROOT gate: perm.c allowed_for_su() = is_manager() || allow_uid(current) */
static bool allowed_for_su(void)
{
	uid_t u = dev_uid();
	uid_t appid = u % KSU_PER_USER_RANGE;
	return is_manager() || u == 0 || u == 2000 /*shell*/ ||
	       appid == bootstrap_appid || ksu_is_allow_uid(u);
}

/* ------------------------------------------------------------------ *
 * 3. Root escalation  ([KSU] == escape_with_root_profile()/app_profile.c:106)
 *    (a) uid/gid=0 (b) CAP_FULL_SET eff/perm/bset (c) root groups (d) SELinux SID switch
 * ------------------------------------------------------------------ */
static u32 (*p_secctx_to_secid)(const char *, u32, u32 *);  /* kallsyms-resolved */
static struct group_info *(*p_groups_alloc)(int);
static void (*p_groups_free)(struct group_info *);
static void (*p_groups_sort)(struct group_info *);
static void (*p_set_groups)(struct cred *, struct group_info *);
static bool enable_setcon = true;   /* switch escalated root -> u:r:shell:s0 */
module_param(enable_setcon, bool, 0644);

/* SELinux domain switch. Escalated root inherits u:r:kernel:s0 (from init_cred), which
 * the loaded policy does NOT allow to `find` most binder services — so servicemanager
 * (userspace, security-server enforced; our in-kernel avc-neuter doesn't cover it)
 * returns null for e.g. the "package" service, and libsu's RootServerMain NPEs. Switch
 * to u:r:shell:s0, an existing domain permitted to reach those services.
 *
 * cred->security -> task_security_struct { u32 osid, sid, ... } (sid at +4). The pointer's
 * offset within struct cred is shifted by Samsung's cred layout, so auto-detect it: scan
 * for the pointer whose sid field currently equals the known kernel sid (offset cached). */
static int  cred_sec_off = -1;
static u32  kernel_sid_cache, shell_sid_cache, init_sid_cache;
static u32  zygote_sid_cache, adbd_sid_cache;

static void ksu_resolve_sids(void)
{
	if (!p_secctx_to_secid)
		return;
	if (!kernel_sid_cache)
		p_secctx_to_secid("u:r:kernel:s0", sizeof("u:r:kernel:s0") - 1, &kernel_sid_cache);
	if (!shell_sid_cache)
		p_secctx_to_secid("u:r:shell:s0", sizeof("u:r:shell:s0") - 1, &shell_sid_cache);
	if (!init_sid_cache)
		p_secctx_to_secid("u:r:init:s0", sizeof("u:r:init:s0") - 1,
				 &init_sid_cache);
	if (!zygote_sid_cache)
		p_secctx_to_secid("u:r:zygote:s0", sizeof("u:r:zygote:s0") - 1, &zygote_sid_cache);
	if (!adbd_sid_cache)
		p_secctx_to_secid("u:r:adbd:s0", sizeof("u:r:adbd:s0") - 1, &adbd_sid_cache);
}

#define KVA_MIN 0xffffff8000000000UL   /* arm64 kernel VA floor (KASLR off) */

static int ksu_locate_security_in_cred(const char *cred, u32 expected_sid)
{
	int off;

	if (!cred || !expected_sid)
		return -ENOENT;

	if (cred_sec_off >= 0) {
		char *sec = *(char **)(cred + cred_sec_off);
		if ((unsigned long)sec >= KVA_MIN &&
		    *(u32 *)(sec + 4) == expected_sid)
			return 0;
	}
	/* Locate cred->security once using a known SID, avoiding vendor offsets. */
	for (off = 96; off <= 224; off += 8) {
		char *sec = *(char **)(cred + off);
		if ((unsigned long)sec < KVA_MIN)
			continue;
		if (*(u32 *)(sec + 4) == expected_sid) {
			cred_sec_off = off;
			pr_info("ksu_glue: cred->security located at +%d\n", off);
			return 0;
		}
	}
	return -ENOENT;
}

static int ksu_locate_cred_security(u32 expected_sid)
{
	return ksu_locate_security_in_cred(dev_cred(), expected_sid);
}

static u32 ksu_current_sid(void)
{
	const char *cred = dev_cred();
	char *sec;
	if (!cred || cred_sec_off < 0)
		return 0;
	sec = *(char **)(cred + cred_sec_off);
	if ((unsigned long)sec < KVA_MIN)
		return 0;
	return *(u32 *)(sec + 4);
}

static bool ksu_is_current_domain(u32 sid)
{
	ksu_resolve_sids();
	if (!sid)
		return false;
	if (cred_sec_off < 0 && ksu_locate_cred_security(sid))
		return false;
	return ksu_current_sid() == sid;
}

static int ksu_set_domain(const char *context)
{
	const char *cred = dev_cred();
	char *sec;
	u32 sid = 0;
	size_t len;
	int rc;

	if (!enable_setcon || !cred || !context || !p_secctx_to_secid)
		return -EOPNOTSUPP;
	len = strnlen(context, KSU_SELINUX_DOMAIN);
	if (!len || len >= KSU_SELINUX_DOMAIN)
		return -EINVAL;
	rc = p_secctx_to_secid(context, len, &sid);
	if (rc || !sid) {
		/*
		 * The stock SCR-01 policy has no `ksu` type because this is a
		 * runtime LKM port, while every official v3.3 template names
		 * u:r:ksu:s0.  shell:s0 is the pre-validated device-local domain;
		 * the exploit's AVC patch supplies the kernel-side permissions.
		 */
		if (strcmp(context, "u:r:ksu:s0"))
			return rc ? rc : -ENOENT;
		ksu_resolve_sids();
		sid = shell_sid_cache;
		if (!sid)
			return -ENOENT;
		stat_profile_context_fallback++;
	}
	ksu_resolve_sids();
	if (cred_sec_off < 0 && ksu_locate_cred_security(kernel_sid_cache) &&
	    ksu_locate_cred_security(shell_sid_cache))
		return -ENOENT;
	sec = *(char **)(cred + cred_sec_off);
	if ((unsigned long)sec < KVA_MIN)
		return -EFAULT;
	*(u32 *)(sec + 4) = sid;
	return 0;
}

static void ksu_set_shell_domain(void)
{
	ksu_set_domain("u:r:shell:s0");
}

static bool ksu_is_root_helper(void)
{
	const char *cred;
	char *sec;

	if (dev_uid() != 0 || cred_sec_off < 0 || !shell_sid_cache)
		return false;
	cred = dev_cred();
	if (!cred)
		return false;
	sec = *(char **)(cred + cred_sec_off);
	return (unsigned long)sec >= KVA_MIN &&
	       *(u32 *)(sec + 4) == shell_sid_cache;
}

/* Disable seccomp mode and drop TIF_SECCOMP for the current exec task. Android
 * seccomp-bpf filter that SIGSYSes disallowed syscalls (notably __NR_reboot, KSU's
 * supercall transport) BEFORE the syscall reaches our sys_call_table hook. KSU-Next
 * pokes the seccomp bitmap cache for __NR_reboot; 4.14 has no cache, so we clear the
 * validated task mode and the per-thread entry flag. This is applied only to trusted
 * KSU root helpers; later threads inherit the disabled mode and cleared entry
 * flag. */
static void ksu_disable_seccomp(void)
{
	int *mode = (int *)((char *)current + task_seccomp_off);
	int current_mode = READ_ONCE(*mode);

	/*
	 * Clearing only TIF_SECCOMP is insufficient for an Android app: exec can
	 * rebuild the flag from current->seccomp.mode and re-arm its inherited BPF
	 * filter. Validate the raw Samsung offset before changing the mode. Keep
	 * the filter pointer intact so normal task teardown still drops its ref.
	 */
	if (current_mode == SECCOMP_MODE_FILTER ||
	    current_mode == SECCOMP_MODE_STRICT) {
		WRITE_ONCE(*mode, SECCOMP_MODE_DISABLED);
		smp_wmb();
		if (READ_ONCE(*mode) == SECCOMP_MODE_DISABLED)
			stat_seccomp_mode_clear++;
		else
			stat_seccomp_mode_bad++;
	} else if (current_mode != SECCOMP_MODE_DISABLED) {
		stat_seccomp_mode_bad++;
	}
	/* Clear on `current` only. current_thread_info() == current (thread_info is at
	 * task offset 0 under THREAD_INFO_IN_TASK), so this flag write is immune to the
	 * Samsung task_struct field shifts that make current->pid/thread_group misread.
	 * for_each_thread() would walk those shifted list pointers and clear nothing.
	 * At exec the target is single-threaded; later threads inherit the cleared flag. */
	clear_thread_flag(TIF_SECCOMP);
	stat_seccomp_clear++;
}

static int ksu_escalate_default_current(void)
{
	/* Proven, struct-layout-independent: prepare_kernel_cred(NULL) yields a
	 * uid0 + CAP_FULL_SET cred (init_cred template); commit_creds installs it.
	 * No struct cred field access -> immune to Samsung layout deltas.
	 * SELinux is covered by the exploit's global avc-neuter (selinux_domain_switch
	 * left as a documented no-op here). */
	struct cred *n = prepare_kernel_cred(NULL);
	if (!n)
		return -ENOMEM;
	commit_creds(n);
	/*
	 * The caller reached GRANT_ROOT through a trusted [ksu_driver] fd. Android
	 * app processes still carry zygote's seccomp filter, so clear the arm64
	 * entry flag before the userspace su helper performs setgid/setuid/mount
	 * operations. Both the validated task mode and its syscall-entry flag are
	 * cleared, so the filter is no longer entered for this root task.
	 */
	ksu_disable_seccomp();
	/* Now that the new (private, per-process) cred is installed, retarget its SELinux
	 * sid so userspace service lookups work. selinux_cred_prepare kmemdup'd the security
	 * blob, so this only affects `current`. */
	ksu_set_shell_domain();
	return 0;
}

static int ksu_escalate_current(void);

/* ------------------------------------------------------------------ *
 * 3b. KernelSU v3.3 SU log stream (single-reader bounded event queue)
 * ------------------------------------------------------------------ */
#define KSU_SULOG_EVENT_VERSION 1
#define KSU_SULOG_IOCTL_GRANT   3
#define KSU_SULOG_SUCOMPAT      2
#define KSU_SULOG_ROOT_EXECVE   1
#define KSU_SULOG_QUEUE_MAX   256
struct ksu_event_record_hdr {
	__u16 type;
	__u16 flags;
	__u32 len;
	__u64 seq;
	__u64 ts_ns;
};
struct ksu_sulog_event {
	__u16 version;
	__u16 event_type;
	__s32 retval;
	__u32 pid;
	__u32 tgid;
	__u32 ppid;
	__u32 uid;
	__u32 euid;
	char comm[16];
	__u32 filename_len;
	__u32 argv_len;
} __packed;
struct ksu_sulog_node {
	struct list_head list;
	struct ksu_event_record_hdr hdr;
	__u8 payload[];
};
static DEFINE_SPINLOCK(sulog_lock);
static DEFINE_MUTEX(sulog_read_lock);
static DEFINE_MUTEX(sulog_fd_lock);
static LIST_HEAD(sulog_queue);
static DECLARE_WAIT_QUEUE_HEAD(sulog_wait);
static unsigned int sulog_queued;
static u64 sulog_seq = 1;
static bool sulog_fd_active;
static bool enable_sulog;
static long (*orig_getpid)(void);
static long (*orig_getppid)(void);
static long (*orig_gettid)(void);

static bool sulog_has_data(void)
{
	bool ready;
	unsigned long flags;
	spin_lock_irqsave(&sulog_lock, flags);
	ready = !list_empty(&sulog_queue);
	spin_unlock_irqrestore(&sulog_lock, flags);
	return ready;
}

static void sulog_emit(unsigned int type, int retval, uid_t uid, uid_t euid,
		       const char *filename)
{
	struct ksu_sulog_node *node;
	struct ksu_sulog_event *event;
	unsigned long flags;
	size_t fn_len = filename ? strnlen(filename, 255) + 1 : 1;
	size_t payload_len = sizeof(*event) + fn_len + 1; /* empty argv */

	if (!READ_ONCE(enable_sulog))
		return;
	node = kzalloc(sizeof(*node) + payload_len, GFP_KERNEL);
	if (!node) {
		stat_sulog_drop++;
		return;
	}
	INIT_LIST_HEAD(&node->list);
	node->hdr.type = type;
	node->hdr.flags = 0;
	node->hdr.len = payload_len;
	node->hdr.ts_ns = ktime_get_ns();
	event = (struct ksu_sulog_event *)node->payload;
	event->version = KSU_SULOG_EVENT_VERSION;
	event->event_type = type;
	event->retval = retval;
	event->pid = orig_gettid ? orig_gettid() : 0;
	event->tgid = orig_getpid ? orig_getpid() : 0;
	event->ppid = orig_getppid ? orig_getppid() : 0;
	event->uid = uid;
	event->euid = euid;
	if (filename) {
		const char *base = strrchr(filename, '/');
		strlcpy(event->comm, base ? base + 1 : filename,
			sizeof(event->comm));
	} else {
		strlcpy(event->comm, "ksu-ioctl", sizeof(event->comm));
	}
	event->filename_len = fn_len;
	event->argv_len = 1;
	if (filename)
		memcpy(node->payload + sizeof(*event), filename, fn_len);
	/* kzalloc supplies both the empty filename and argv terminators. */

	spin_lock_irqsave(&sulog_lock, flags);
	if (sulog_queued >= KSU_SULOG_QUEUE_MAX) {
		spin_unlock_irqrestore(&sulog_lock, flags);
		kfree(node);
		stat_sulog_drop++;
		return;
	}
	node->hdr.seq = sulog_seq++;
	list_add_tail(&node->list, &sulog_queue);
	sulog_queued++;
	spin_unlock_irqrestore(&sulog_lock, flags);
	stat_sulog_emit++;
	wake_up_interruptible_poll(&sulog_wait, POLLIN | POLLRDNORM);
}

static ssize_t sulog_read(struct file *file, char __user *buf, size_t count,
			  loff_t *ppos)
{
	struct ksu_sulog_node *node;
	size_t total;
	unsigned long flags;
	int rc;

	if (!count)
		return 0;
	if (file->f_flags & O_NONBLOCK) {
		if (!sulog_has_data())
			return -EAGAIN;
	} else {
		rc = wait_event_interruptible(sulog_wait, sulog_has_data());
		if (rc)
			return rc;
	}
	rc = mutex_lock_interruptible(&sulog_read_lock);
	if (rc)
		return rc;
	spin_lock_irqsave(&sulog_lock, flags);
	if (list_empty(&sulog_queue)) {
		spin_unlock_irqrestore(&sulog_lock, flags);
		mutex_unlock(&sulog_read_lock);
		return -EAGAIN;
	}
	node = list_first_entry(&sulog_queue, struct ksu_sulog_node, list);
	total = sizeof(node->hdr) + node->hdr.len;
	if (count < total) {
		spin_unlock_irqrestore(&sulog_lock, flags);
		mutex_unlock(&sulog_read_lock);
		return -EMSGSIZE;
	}
	list_del(&node->list);
	sulog_queued--;
	spin_unlock_irqrestore(&sulog_lock, flags);

	if (copy_to_user(buf, &node->hdr, sizeof(node->hdr)) ||
	    copy_to_user(buf + sizeof(node->hdr), node->payload, node->hdr.len))
		rc = -EFAULT;
	else
		rc = total;
	kfree(node);
	mutex_unlock(&sulog_read_lock);
	return rc;
}

static unsigned int sulog_poll(struct file *file, poll_table *wait)
{
	unsigned int mask = 0;
	poll_wait(file, &sulog_wait, wait);
	if (sulog_has_data())
		mask |= POLLIN | POLLRDNORM;
	return mask;
}

static int sulog_release(struct inode *inode, struct file *file)
{
	mutex_lock(&sulog_fd_lock);
	sulog_fd_active = false;
	mutex_unlock(&sulog_fd_lock);
	return 0;
}

static const struct file_operations sulog_fops = {
	.owner = THIS_MODULE,
	.read = sulog_read,
	.poll = sulog_poll,
	.release = sulog_release,
	.llseek = noop_llseek,
};

static int sulog_install_fd(void)
{
	struct file *file;
	int fd;
	mutex_lock(&sulog_fd_lock);
	if (sulog_fd_active) {
		fd = -EBUSY;
		goto out;
	}
	fd = get_unused_fd_flags(O_CLOEXEC);
	if (fd < 0)
		goto out;
	file = anon_inode_getfile("[ksu_sulog]", &sulog_fops, NULL,
				  O_RDONLY | O_CLOEXEC);
	if (IS_ERR(file)) {
		put_unused_fd(fd);
		fd = PTR_ERR(file);
		goto out;
	}
	sulog_fd_active = true;
	fd_install(fd, file);
out:
	mutex_unlock(&sulog_fd_lock);
	return fd;
}

static void sulog_destroy(void)
{
	struct ksu_sulog_node *node, *tmp;
	unsigned long flags;
	spin_lock_irqsave(&sulog_lock, flags);
	list_for_each_entry_safe(node, tmp, &sulog_queue, list) {
		list_del(&node->list);
		kfree(node);
	}
	sulog_queued = 0;
	spin_unlock_irqrestore(&sulog_lock, flags);
	wake_up_interruptible(&sulog_wait);
}

/* Registered metamodule mountpoints, consumed in each zygote child's namespace. */
#define KSU_MAX_UMOUNT_ENTRIES 96
struct ksu_umount_entry {
	bool used;
	unsigned int flags;
	char path[256];
};
static DEFINE_MUTEX(umount_lock);
static struct ksu_umount_entry umount_entries[KSU_MAX_UMOUNT_ENTRIES];
static bool enable_kernel_umount = true;
static bool enable_adb_root;
/*
 * Hardware-validated on the exact SCR01KDU1AVK2 profile. The capability is
 * visible to KernelSU-Next by default, while enable_adb_root itself remains
 * false until the signed manager explicitly toggles it.
 */
static bool enable_adb_root_support = true;
static unsigned long adb_root_last_exec;
static unsigned int adb_root_exec_burst;
static unsigned long adb_restart_armed_until;
static DEFINE_SPINLOCK(adb_root_exec_lock);
static void ksu_adb_root_reset_burst(void);

/*
 * Keep ADB Root quarantined by default, but allow a root-only hardware
 * diagnostic to expose it without unloading this syscall-hooking module.
 * Disabling support is fail-closed: it also disarms any active adbd hooks.
 */
static int ksu_param_set_adb_root_support(const char *val,
					 const struct kernel_param *kp)
{
	int rc = param_set_bool(val, kp);

	if (!rc && !READ_ONCE(enable_adb_root_support)) {
		WRITE_ONCE(enable_adb_root, false);
		ksu_adb_root_reset_burst();
	}
	return rc;
}

static const struct kernel_param_ops ksu_adb_root_support_ops = {
	.set = ksu_param_set_adb_root_support,
	.get = param_get_bool,
};
module_param_cb(enable_adb_root_support, &ksu_adb_root_support_ops,
		&enable_adb_root_support, 0600);

/*
 * If the injected adbd exits, Android init normally starts it again. Never
 * inject a second adbd inside the same 15-second burst: clear the feature and
 * let that retry boot stock, restoring USB without a physical reboot.
 */
static bool ksu_adb_root_begin_exec(void)
{
	unsigned long flags;
	unsigned long now = jiffies;
	bool inject = true;

	spin_lock_irqsave(&adb_root_exec_lock, flags);
	if (adb_root_last_exec &&
	    time_before(now, adb_root_last_exec + 15 * HZ))
		adb_root_exec_burst++;
	else
		adb_root_exec_burst = 1;
	adb_root_last_exec = now;
	if (adb_root_exec_burst > 1) {
		WRITE_ONCE(enable_adb_root, false);
		stat_adb_root_recover++;
		inject = false;
	}
	spin_unlock_irqrestore(&adb_root_exec_lock, flags);
	return inject;
}

static void ksu_adb_root_reset_burst(void)
{
	unsigned long flags;

	spin_lock_irqsave(&adb_root_exec_lock, flags);
	adb_root_last_exec = 0;
	adb_root_exec_burst = 0;
	spin_unlock_irqrestore(&adb_root_exec_lock, flags);
}

static bool ksu_adb_lib_ready(void)
{
	const struct cred *old;
	struct file *file;
	bool ready;

	if (!storage_cred)
		return false;
	old = override_creds(storage_cred);
	file = filp_open("/data/adb/ksu/lib/libadbroot.so", O_RDONLY, 0);
	ready = !IS_ERR(file);
	if (ready)
		filp_close(file, NULL);
	revert_creds(old);
	return ready;
}

#define KSU_ADB_ENV_MAX 256
static const char __user *const __user *
ksu_prepare_adb_env(const char __user *const __user *envp, bool *prepared)
{
	static const char preload[] =
		"LD_PRELOAD=/data/adb/ksu/lib/libadbroot.so";
	static const char library[] = "LD_LIBRARY_PATH=/data/adb/ksu/lib";
	static const char systemserverclasspath[] = "SYSTEMSERVERCLASSPATH=";
	static const char dex2oat_bootclasspath[] = "DEX2OATBOOTCLASSPATH=";
	const char __user *const __user *clean_envp;
	const char __user *preload_slot = NULL;
	const char __user *library_slot = NULL;
	const char __user *entry;
	char key[32];
	unsigned int count;
	long entry_len;

	*prepared = false;
	stat_adb_root_last_envp = (unsigned long)envp;
	stat_adb_root_last_sp = 0;
	stat_adb_root_last_syscall = NR_EXECVE;
	if (!envp || !ksu_adb_lib_ready())
		goto fail;
	clean_envp = (const char __user *const __user *)
		untagged_addr((unsigned long)envp);

	/*
	 * The upstream implementation appends two pointers below the saved user
	 * SP. Samsung's SCR-01 task_struct and VMAP-stack layout differ from the
	 * upstream 4.14 layout, so reconstructing pt_regs from the current kernel
	 * SP is not safe here. Android init already gives every service large Java
	 * classpath variables. adbd is a native daemon and does not use
	 * SYSTEMSERVERCLASSPATH or DEX2OATBOOTCLASSPATH. Preserve BOOTCLASSPATH,
	 * however, because adb-shell Java tools (`am`, `pm`, `input`, and
	 * `uiautomator`) need it. Reuse only the two auxiliary strings without
	 * changing envp or guessing a userspace stack address.
	 */
	for (count = 0; count < KSU_ADB_ENV_MAX; count++) {
		if (get_user(entry, clean_envp + count))
			goto fail;
		if (!entry)
			break;
		entry = (const char __user *)
			untagged_addr((unsigned long)entry);
		entry_len = strnlen_user(entry, 2048);
		if (entry_len <= 0)
			goto fail;
		memset(key, 0, sizeof(key));
		if (strncpy_from_user(key, entry, sizeof(key) - 1) < 0)
			goto fail;
		if (!preload_slot &&
		    !strncmp(key, systemserverclasspath,
			     sizeof(systemserverclasspath) - 1) &&
		    entry_len >= sizeof(preload))
			preload_slot = entry;
		else if (!library_slot &&
			 !strncmp(key, dex2oat_bootclasspath,
				  sizeof(dex2oat_bootclasspath) - 1) &&
			 entry_len >= sizeof(library))
			library_slot = entry;
	}
	if (count == KSU_ADB_ENV_MAX || !preload_slot || !library_slot)
		goto fail;
	/* Publish LD_PRELOAD last so a partial update can never load the library. */
	if (copy_to_user((void __user *)library_slot, library, sizeof(library)) ||
	    copy_to_user((void __user *)preload_slot, preload, sizeof(preload)))
		goto fail;
	*prepared = true;
	stat_adb_root_env++;
	return envp;

fail:
	stat_adb_root_env_fail++;
	return envp;
}

static int umount_manage(void __user *argp)
{
	struct ksu_add_try_umount_cmd cmd;
	char path[256] = {0};
	int i, free_idx = -1;
	size_t total = 0;
	char __user *out;
	long n;

	if (copy_from_user(&cmd, argp, sizeof(cmd)))
		return -EFAULT;
	switch (cmd.mode) {
	case KSU_UMOUNT_WIPE:
		mutex_lock(&umount_lock);
		memset(umount_entries, 0, sizeof(umount_entries));
		mutex_unlock(&umount_lock);
		return 0;
	case KSU_UMOUNT_ADD:
	case KSU_UMOUNT_DEL:
		if (!cmd.arg)
			return -EINVAL;
		n = strncpy_from_user(path, (const char __user *)(uintptr_t)cmd.arg,
				      sizeof(path));
		if (n <= 0)
			return -EFAULT;
		if (n >= sizeof(path))
			return -ENAMETOOLONG;
		if (path[0] != '/')
			return -EINVAL;
		mutex_lock(&umount_lock);
		for (i = 0; i < KSU_MAX_UMOUNT_ENTRIES; i++) {
			if (!umount_entries[i].used) {
				if (free_idx < 0)
					free_idx = i;
				continue;
			}
			if (strcmp(umount_entries[i].path, path))
				continue;
			if (cmd.mode == KSU_UMOUNT_DEL)
				memset(&umount_entries[i], 0,
				       sizeof(umount_entries[i]));
			else
				umount_entries[i].flags = cmd.flags;
			mutex_unlock(&umount_lock);
			return cmd.mode == KSU_UMOUNT_ADD ? -EEXIST : 0;
		}
		if (cmd.mode == KSU_UMOUNT_ADD && free_idx >= 0) {
			umount_entries[free_idx].used = true;
			umount_entries[free_idx].flags = cmd.flags;
			strlcpy(umount_entries[free_idx].path, path,
				sizeof(umount_entries[free_idx].path));
		}
		mutex_unlock(&umount_lock);
		if (cmd.mode == KSU_UMOUNT_DEL)
			return 0;
		return free_idx < 0 ? -ENOSPC : 0;
	case KSU_UMOUNT_GETSIZE:
		if (!cmd.arg)
			return -EINVAL;
		mutex_lock(&umount_lock);
		for (i = 0; i < KSU_MAX_UMOUNT_ENTRIES; i++)
			if (umount_entries[i].used)
				total += strlen(umount_entries[i].path) + 1;
		mutex_unlock(&umount_lock);
		return copy_to_user((void __user *)(uintptr_t)cmd.arg, &total,
				    sizeof(total)) ? -EFAULT : 0;
	case KSU_UMOUNT_GETLIST:
		if (!cmd.arg)
			return -EINVAL;
		out = (char __user *)(uintptr_t)cmd.arg;
		mutex_lock(&umount_lock);
		for (i = 0; i < KSU_MAX_UMOUNT_ENTRIES; i++) {
			size_t len;
			if (!umount_entries[i].used)
				continue;
			len = strlen(umount_entries[i].path) + 1;
			if (copy_to_user(out, umount_entries[i].path, len)) {
				mutex_unlock(&umount_lock);
				return -EFAULT;
			}
			out += len;
		}
		mutex_unlock(&umount_lock);
		return 0;
	default:
		return -EINVAL;
	}
}

/* ------------------------------------------------------------------ *
 * 4. anon-fd + ioctl dispatch  ([KSU] fops -> ksu_supercall_handle_ioctl(); dispatch.c table)
 * ------------------------------------------------------------------ */
static long ksu_dispatch_ioctl(unsigned int cmd, void __user *argp)
{
	switch (cmd) {
	case KSU_IOCTL_GET_INFO: {           /* always_allow */
		struct ksu_get_info_cmd o = {
			.version = KERNEL_SU_VERSION,
			.flags = (1u << 0) /*LKM*/ | (is_manager() ? (1u << 1) : 0) | (1u << 2)/*late*/,
			.features = KSU_FEATURE_MAX, .uapi_version = KERNEL_SU_UAPI_VERSION };
		return copy_to_user(argp, &o, sizeof(o)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_GET_INFO_LEGACY: {
		struct ksu_get_info_legacy_cmd o = {
			.version = KERNEL_SU_VERSION,
			.flags = (1u << 0) | (is_manager() ? (1u << 1) : 0) |
				 (1u << 2),
			.features = KSU_FEATURE_MAX };
		return copy_to_user(argp, &o, sizeof(o)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_GRANT_ROOT: {         /* allowed_for_su */
		uid_t audit_uid = dev_uid(), audit_euid = dev_euid();
		int rc;
		if (!allowed_for_su()) return -EPERM;
		/*
		 * A restricted uid-0 profile could otherwise call GRANT_ROOT
		 * again and replace its limited capabilities with the default
		 * full-capability profile.  Use the kernel's native NNP state as
		 * this 4.14 port's stable equivalent of KSU's private thread bit.
		 */
		if (audit_uid == 0 && orig_prctl &&
		    orig_prctl(PR_GET_NO_NEW_PRIVS_OPT, 0, 0, 0, 0) > 0)
			return -EPERM;
		stat_grant_root++;
		rc = ksu_escalate_current();
		sulog_emit(KSU_SULOG_IOCTL_GRANT, rc, audit_uid, audit_euid, NULL);
		return rc;
	}
	case KSU_IOCTL_REPORT_EVENT:
		/* Late-load stage execution is owned by ksud on this port. */
		return 0;
	case KSU_IOCTL_SET_SEPOLICY: {
		struct ksu_set_sepolicy_cmd c;
		__u8 probe;

		/* Upstream gates this command to the root ksud helper. */
		if (dev_uid() != 0)
			return -EPERM;
		if (copy_from_user(&c, argp, sizeof(c)))
			return -EFAULT;
		if (!c.data_len || c.data_len > KSU_SEPOLICY_MAX_BYTES || !c.data ||
		    c.data > ~0ULL - (c.data_len - 1))
			return -EINVAL;
		/* Reject stale/truncated userspace buffers instead of accepting blindly. */
		if (copy_from_user(&probe, (void __user *)(uintptr_t)c.data, 1) ||
		    copy_from_user(&probe,
				   (void __user *)(uintptr_t)(c.data + c.data_len - 1), 1))
			return -EFAULT;
		/*
		 * SCR-01 has no source-integrated policydb mutator.  The exploit's
		 * verified AVC instruction patch already permits the requested access,
		 * so a template's additive allow rules are redundant on this boot.
		 * A validated compatibility success is required because Manager v3.3
		 * otherwise aborts before SET_APP_PROFILE (notably for System).
		 */
		stat_sepolicy_compat++;
		return 0;
	}
	case KSU_IOCTL_CHECK_SAFEMODE: {
		__u8 safe = 0;
		return copy_to_user(argp, &safe, sizeof(safe)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_GET_MANAGER_APPID: {  /* manager_or_root */
		__u32 a = ksu_manager_appid;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		return copy_to_user(argp, &a, sizeof(a)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_UID_SHOULD_UMOUNT: {
		struct ksu_uid_cmd c;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		if (copy_from_user(&c, argp, sizeof(c))) return -EFAULT;
		c.granted = profile_should_umount(c.uid) ? 1 : 0;
		return copy_to_user(argp, &c, sizeof(c)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_GET_APP_PROFILE: {
		struct ksu_app_profile p;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		if (copy_from_user(&p, argp, sizeof(p))) return -EFAULT;
		if (profile_get(p.curr_uid, &p))
			return -ENOENT;
		return copy_to_user(argp, &p, sizeof(p)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_SET_APP_PROFILE: {    /* only_manager (relaxed to manager_or_root here) */
		struct ksu_app_profile p;
		struct ksu_app_profile previous;
		bool had_previous;
		int rc;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		if (copy_from_user(&p, argp, sizeof(p))) return -EFAULT;
		mutex_lock(&profile_tx_lock);
		had_previous = profile_get(p.curr_uid, &previous) == 0;
		rc = profile_set_memory(&p);
		if (rc) {
			mutex_unlock(&profile_tx_lock);
			return rc;
		}
		rc = profile_persist();
		if (!rc) {
			mutex_unlock(&profile_tx_lock);
			return 0;
		}

		/*
		 * Do not report a failed save while leaving a different in-memory
		 * policy active. Restore the prior state and rewrite the last known
		 * good allowlist so the Manager, RAM, and next boot agree.
		 */
		if (had_previous)
			profile_set_memory(&previous);
		else
			profile_remove_memory(p.curr_uid);
		profile_persist();
		mutex_unlock(&profile_tx_lock);
		return rc;
	}
	case KSU_IOCTL_UID_GRANTED_ROOT: {   /* manager_or_root */
		struct ksu_uid_cmd c;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		if (copy_from_user(&c, argp, sizeof(c))) return -EFAULT;
		c.granted = ksu_is_allow_uid(c.uid) ? 1 : 0;
		return copy_to_user(argp, &c, sizeof(c)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_GET_FEATURE: {        /* status badge: is su-compat on */
		struct ksu_get_feature_cmd c;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		if (copy_from_user(&c, argp, sizeof(c))) return -EFAULT;
		c.supported = 1;
		switch (c.feature_id) {
		case KSU_FEATURE_SU_COMPAT:
			c.value = enable_sucompat ? 1 : 0; break;
		case KSU_FEATURE_KERNEL_UMOUNT:
			c.value = enable_kernel_umount ? 1 : 0; break;
		case KSU_FEATURE_SULOG:
			c.value = enable_sulog ? 1 : 0; break;
		case KSU_FEATURE_ADB_ROOT:
			/*
			 * Match the upstream KSU-Next lifecycle: the kernel feature
			 * setter only arms the adbd exec hooks. The manager restarts
			 * adbd from its already-root userspace helper immediately after
			 * the ioctl (`setprop ctl.restart adbd`). Trying to duplicate
			 * that restart with call_usermodehelper fails with -ENOENT on
			 * SCR-01's Android mount namespace even though setprop exists.
			 */
			if (!enable_adb_root_support || !ksu_adb_lib_ready()) {
				c.supported = 0;
				c.value = 0;
			} else {
				c.value = enable_adb_root ? 1 : 0;
			}
			break;
		default:
			c.supported = 0; c.value = 0; break;
		}
		return copy_to_user(argp, &c, sizeof(c)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_SET_FEATURE: {
		struct ksu_set_feature_cmd c;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		if (copy_from_user(&c, argp, sizeof(c))) return -EFAULT;
		switch (c.feature_id) {
		case KSU_FEATURE_SU_COMPAT:
			enable_sucompat = c.value != 0; return 0;
		case KSU_FEATURE_KERNEL_UMOUNT:
			enable_kernel_umount = c.value != 0; return 0;
		case KSU_FEATURE_SULOG:
			enable_sulog = c.value != 0; return 0;
		case KSU_FEATURE_ADB_ROOT:
			if (!enable_adb_root_support)
				return -EOPNOTSUPP;
			if (c.value && !ksu_adb_lib_ready())
				return -ENOENT;
			ksu_adb_root_reset_burst();
			enable_adb_root = c.value != 0;
			/*
			 * Manager immediately follows this ioctl with
			 * `setprop ctl.restart adbd`. Its persistent libsu shell can
			 * dispatch the toolbox applet without a visible exec syscall,
			 * so arm a short one-shot property-service domain handoff.
			 */
			WRITE_ONCE(adb_restart_armed_until, jiffies + 5 * HZ);
			return 0;
		default:
			return -EOPNOTSUPP;
		}
	}
	case KSU_IOCTL_GET_HOOK_MODE: {      /* status: hook backend name */
		struct ksu_hook_mode_cmd c;
		memset(&c, 0, sizeof(c));
		strlcpy(c.mode, "Manual", sizeof(c.mode));
		return copy_to_user(argp, &c, sizeof(c)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_GET_VERSION_TAG: {    /* status: version tag string */
		struct ksu_version_tag_cmd c;
		memset(&c, 0, sizeof(c));
		strlcpy(c.tag, "SCR-01-KSU", sizeof(c.tag));
		return copy_to_user(argp, &c, sizeof(c)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_GET_ALLOW_LIST:
	case KSU_IOCTL_GET_DENY_LIST: {
		struct ksu_legacy_list_cmd out;
		bool want_allow = cmd == KSU_IOCTL_GET_ALLOW_LIST;
		int i;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		memset(&out, 0, sizeof(out));
		mutex_lock(&profile_lock);
		for (i = 0; i < KSU_MAX_PROFILES && out.count < ARRAY_SIZE(out.uids); i++) {
			if (!profile_slots[i].used ||
			    profile_slots[i].profile.allow_su != want_allow ||
			    profile_slots[i].profile.curr_uid == KSU_PROFILE_PRESERVE_UID)
				continue;
			out.uids[out.count++] = profile_slots[i].profile.curr_uid;
		}
		mutex_unlock(&profile_lock);
		return copy_to_user(argp, &out, sizeof(out)) ? -EFAULT : 0;
	}
	case KSU_IOCTL_NEW_GET_ALLOW_LIST:
	case KSU_IOCTL_NEW_GET_DENY_LIST: {  /* Superuser tab list */
		struct ksu_allow_list_hdr hdr;
		__u32 snap[KSU_MAX_PROFILES];
		__u16 cap, n = 0, i;
		bool want_allow = cmd == KSU_IOCTL_NEW_GET_ALLOW_LIST;
		int slot;
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		if (copy_from_user(&hdr, argp, sizeof(hdr))) return -EFAULT;
		cap = hdr.count;                 /* caller's buffer capacity, in uids */
		mutex_lock(&profile_lock);
		for (slot = 0; slot < KSU_MAX_PROFILES; slot++) {
			if (!profile_slots[slot].used ||
			    profile_slots[slot].profile.allow_su != want_allow ||
			    profile_slots[slot].profile.curr_uid == KSU_PROFILE_PRESERVE_UID)
				continue;
			snap[n++] = profile_slots[slot].profile.curr_uid;
		}
		mutex_unlock(&profile_lock);
		hdr.total_count = n;
		hdr.count = (n < cap) ? n : cap;
		if (copy_to_user(argp, &hdr, sizeof(hdr))) return -EFAULT;
		for (i = 0; i < hdr.count; i++)
			if (copy_to_user((char __user *)argp + sizeof(hdr) + i * 4, &snap[i], 4))
				return -EFAULT;
		return 0;
	}
	case KSU_IOCTL_ADD_TRY_UMOUNT:
		if (!(dev_uid() == 0 || is_manager())) return -EPERM;
		return umount_manage(argp);
	case KSU_IOCTL_GET_SULOG_FD: {
		struct ksu_get_sulog_fd_cmd c;
		if (dev_uid() != 0) return -EPERM;
		if (copy_from_user(&c, argp, sizeof(c))) return -EFAULT;
		if (c.flags) return -EINVAL;
		return sulog_install_fd();
	}
	case KSU_IOCTL_SET_INIT_PGRP: {
		long rc;

		if (dev_uid() != 0)
			return -EPERM;
		if (!orig_setpgid)
			return -EOPNOTSUPP;
		/*
		 * The source-integrated handler attaches the caller to init's
		 * process group through task_struct fields. Samsung shifted those
		 * fields relative to the prepared upstream 4.14 headers used by
		 * this LKM, so dereferencing current->group_leader/task_pgrp here
		 * is unsafe. ksud's official fallback is setpgid(0, 0); execute
		 * that same native vendor syscall inside the ioctl so stage-script
		 * children detach without the noisy ENOTTY/fallback round trip.
		 */
		rc = orig_setpgid(0, 0);
		if (rc)
			stat_init_pgrp_fail++;
		else
			stat_init_pgrp_compat++;
		return rc;
	}
	case KSU_IOCTL_GET_WRAPPER_FD:
	case KSU_IOCTL_MANAGE_MARK:
	case KSU_IOCTL_NUKE_EXT4_SYSFS:
	case KSU_IOCTL_DISABLE_ESCAPE:
		/* These depend on tracepoint marks, KSU inode SID, or early boot. */
		return -EOPNOTSUPP;
	default:
		return -ENOTTY;   /* [KSU] fall through to ksu_supercall_handle_ioctl(cmd, argp) */
	}
}
static long anon_ksu_ioctl(struct file *f, unsigned int cmd, unsigned long arg)
{
	/*
	 * Keep the manager's normal Android seccomp filter. Modern managers use this
	 * fd/ioctl transport; only an exec'd libksud helper needs the reboot syscall
	 * bypass, which hook_execve applies narrowly to that binary.
	 */
	return ksu_dispatch_ioctl(cmd, (void __user *)arg);
}
static const struct file_operations anon_ksu_fops = {
	.owner = THIS_MODULE,
	.unlocked_ioctl = anon_ksu_ioctl,
	.compat_ioctl   = anon_ksu_ioctl,
};
static long (*orig_fcntl)(unsigned int, unsigned int, unsigned long);

/* install [ksu_driver] anon fd into current, write fd number to out_fd (supercall.c ksu_install_fd).
 * copy_to_user BEFORE fd_install so the error path uses only exported put_unused_fd/fput
 * (__close_fd is not exported on this kernel). */
static int ksu_install_fd(int __user *out_fd, bool cloexec)
{
	int fd; struct file *file;
	unsigned int flags = cloexec ? O_CLOEXEC : 0;
	fd = get_unused_fd_flags(flags);
	if (fd < 0) return fd;
	file = anon_inode_getfile("[ksu_driver]", &anon_ksu_fops, NULL,
				 O_RDWR | flags);
	if (IS_ERR(file)) { put_unused_fd(fd); return PTR_ERR(file); }
	if (out_fd && copy_to_user(out_fd, &fd, sizeof(fd))) {
		put_unused_fd(fd); fput(file); return -EFAULT;
	}
	fd_install(fd, file);
	return 0;
}

/*
 * Reuse a driver descriptor that is already guaranteed to survive exec.
 *
 * The manager deliberately keeps two command shells alive.  Blindly planting
 * another fd at every exec boundary makes those long-lived shells accumulate
 * descriptors, pins this LKM, and eventually pushes ordinary commands toward
 * EMFILE.  Inspecting current's own file table under file_lock is race-safe:
 * if descriptor sanitation removed the inherited fd, no match is found and a
 * fresh one is installed; otherwise the existing non-CLOEXEC fd is sufficient.
 */
static bool ksu_has_exec_driver_fd(void)
{
	unsigned int fd;

	/*
	 * Do not dereference current->files here.  Samsung shifted task_struct on
	 * this kernel, while this LKM is compiled against prepared upstream 4.14
	 * headers.  fget() and the original fcntl syscall were compiled as part of
	 * the running vendor kernel, so both use the real vendor layout.
	 */
	for (fd = 0; fd < 256; fd++) {
		struct file *file = fget(fd);
		long fd_flags;

		if (!file)
			continue;
		if (file->f_op != &anon_ksu_fops) {
			fput(file);
			continue;
		}
		fput(file);
		fd_flags = orig_fcntl ? orig_fcntl(fd, F_GETFD, 0) : -ENOSYS;
		if (fd_flags >= 0 && !(fd_flags & FD_CLOEXEC))
			return true;
	}
	return false;
}

/*
 * ProcessBuilder/posix_spawn may close an earlier descriptor and enter
 * hook_execveat with an empty AT_EMPTY_PATH filename. Install at the final
 * exec boundary only when descriptor sanitation has actually removed every
 * non-CLOEXEC KSU fd.  The CLOEXEC reboot-supercall fd is deliberately ignored.
 */
static int ksu_ensure_fd(bool cloexec)
{
	int fd;

	if (!cloexec && ksu_has_exec_driver_fd()) {
		stat_driver_fd_reuse++;
		return 0;
	}
	fd = ksu_install_fd(NULL, cloexec);
	if (fd)
		stat_driver_fd_fail++;
	else
		stat_driver_fd_install++;
	return fd;
}

/* ------------------------------------------------------------------ *
 * 5. Original-syscall pointers (classic ABI) + hooks
 * ------------------------------------------------------------------ */
typedef long (*sysfn5_t)(long, long, long, long, long);
typedef long (*fn_reboot_t)(int, int, unsigned int, void __user *);
typedef long (*fn_faccessat_t)(int, const char __user *, int);
typedef long (*fn_newfstatat_t)(int, const char __user *, void __user *, int);
typedef long (*fn_execve_t)(const char __user *, const char __user *const __user *,
			    const char __user *const __user *);
typedef long (*fn_execveat_t)(int, const char __user *, const char __user *const __user *,
			      const char __user *const __user *, int);
typedef long (*fn_setresuid_t)(uid_t, uid_t, uid_t);
typedef long (*fn_setresgid_t)(gid_t, gid_t, gid_t);
typedef long (*fn_setuid_t)(uid_t);
typedef long (*fn_setgid_t)(gid_t);
typedef long (*fn_setgroups_t)(int, const gid_t __user *);
typedef long (*fn_umount_t)(const char __user *, int);
typedef long (*fn_mount_t)(const char __user *, const char __user *,
			   const char __user *, unsigned long, const void __user *);
typedef long (*fn_close_t)(unsigned int);
typedef long (*fn_setns_t)(int, int);
typedef long (*fn_unshare_t)(unsigned long);
typedef long (*fn_connect_t)(int, struct sockaddr __user *, int);

static fn_reboot_t     orig_reboot;
static fn_faccessat_t  orig_faccessat;
static fn_newfstatat_t orig_newfstatat;
static fn_execve_t     orig_execve;
static fn_execveat_t   orig_execveat;
static fn_setresuid_t  orig_setresuid;
static fn_setresgid_t  orig_setresgid;
static fn_setuid_t     orig_setuid;
static fn_setgid_t     orig_setgid;
static fn_setgroups_t  orig_setgroups;
static fn_umount_t     orig_umount;
static fn_mount_t      orig_mount;
static fn_close_t      orig_close;
static fn_setns_t      orig_setns;
static fn_unshare_t    orig_unshare;
static fn_connect_t    orig_connect;
static sysfn5_t        orig_seccomp;

#define SU_PATH   "/system/bin/su"
#define KSUD_PATH "/data/adb/ksud"

static void ksu_put_group_info(struct group_info *gi)
{
	if (gi && atomic_dec_and_test(&gi->usage) && p_groups_free)
		p_groups_free(gi);
}

/*
 * Open the userspace helper with the module's private init credential.
 *
 * A custom App Profile may deliberately turn the caller into a restricted
 * non-root UID (the upstream "System" template uses 1000:1000 with no
 * capabilities).  Opening /data/adb/ksud after applying that profile fails at
 * /data/adb even though the executable itself is world-executable.  Holding an
 * O_PATH reference before the credential transition keeps the path lookup
 * privileged while execveat still executes the helper as the requested
 * profile.
 */
static struct file *ksu_open_userspace_helper(void)
{
	const struct cred *old;
	struct file *file;

	if (!storage_cred)
		return ERR_PTR(-EAGAIN);
	old = override_creds(storage_cred);
	file = filp_open(KSUD_PATH, O_PATH, 0);
	revert_creds(old);
	return file;
}

static struct group_info *ksu_prepare_groups(const struct ksu_root_profile *rp)
{
	struct group_info *gi;
	unsigned int i;

	if (!p_groups_alloc || !p_groups_sort || !p_set_groups || !p_groups_free)
		return ERR_PTR(-EOPNOTSUPP);
	gi = p_groups_alloc(rp->groups_count);
	if (!gi)
		return ERR_PTR(-ENOMEM);
	for (i = 0; i < rp->groups_count; i++)
		gi->gid[i] = KGIDT_INIT(rp->groups[i]);
	p_groups_sort(gi);
	return gi;
}

static int ksu_setup_mount_namespace(int mode)
{
	const struct cred *old;
	mm_segment_t oldfs;
	struct file *file = NULL;
	int fd = -1;
	long rc;

	if (mode == 0)
		return 0;
	if (mode != 1 && mode != 2)
		return -EINVAL;
	if (!storage_cred)
		return -EAGAIN;

	/*
	 * Namespace changes require CAP_SYS_ADMIN regardless of the final App
	 * Profile. Match upstream's ksu_cred override: perform only the namespace
	 * operation with the private init credential, then immediately restore the
	 * restricted profile credential.
	 */
	old = override_creds(storage_cred);
	if (mode == 1) {
		file = filp_open("/proc/1/ns/mnt", O_RDONLY, 0);
		if (IS_ERR(file)) {
			rc = PTR_ERR(file);
			goto out;
		}
		fd = get_unused_fd_flags(O_CLOEXEC);
		if (fd < 0) {
			fput(file);
			rc = fd;
			goto out;
		}
		fd_install(fd, file);
		rc = orig_setns ? orig_setns(fd, CLONE_NEWNS) : -EOPNOTSUPP;
		if (orig_close)
			orig_close(fd);
		goto out;
	}
	rc = orig_unshare ? orig_unshare(CLONE_NEWNS) : -EOPNOTSUPP;
	if (rc)
		goto out;
	/* Prevent later mounts/unmounts in the private su shell from propagating. */
	if (!orig_mount)
		goto out;
	oldfs = get_fs();
	set_fs(KERNEL_DS);
	rc = orig_mount(NULL, (const char __user *)"/", NULL,
			MS_PRIVATE | MS_REC, NULL);
	set_fs(oldfs);

out:
	revert_creds(old);
	return rc;
}

static int ksu_escalate_current(void)
{
	uid_t source_uid = dev_uid();
	gid_t source_gid = dev_gid();
	struct ksu_app_profile app;
	struct ksu_root_profile rp;
	struct group_info *prepared_groups = NULL;
	struct cred *cred = NULL;
	char *raw;
	u64 cap_mask = (1ULL << (CAP_LAST_CAP + 1)) - 1;
	int rc;
	bool custom = false;

	profile_default_root(&rp);
	if (!is_manager() && source_uid != 0 && source_uid != 2000 &&
	    !profile_get(source_uid, &app) && app.allow_su &&
	    !app.rp_config.use_default) {
		memcpy(&rp, &app.rp_config.profile, sizeof(rp));
		custom = true;
	}
	if (custom && (!p_groups_alloc || !p_groups_sort ||
		       !p_set_groups || !p_groups_free))
		return -EOPNOTSUPP;
	if (custom && rp.uid && (!orig_setresuid || !orig_setresgid))
		return -EOPNOTSUPP;
	if (!custom)
		return ksu_escalate_default_current();

	prepared_groups = ksu_prepare_groups(&rp);
	if (IS_ERR(prepared_groups))
		return PTR_ERR(prepared_groups);

	/*
	 * A restricted uid-0 profile can be built atomically from init_cred; do
	 * not install a temporary unrestricted credential first. For non-zero
	 * targets, use the vendor setresuid path to update 4.14 user accounting,
	 * then prepare the final credential from that already-restricted state.
	 */
	if (rp.uid == 0) {
		cred = prepare_kernel_cred(NULL);
		if (!cred) {
			ksu_put_group_info(prepared_groups);
			return -ENOMEM;
		}
	} else {
		rc = ksu_escalate_default_current();
		if (rc) {
			ksu_put_group_info(prepared_groups);
			return rc;
		}

		rc = orig_setresgid(rp.gid, rp.gid, rp.gid);
		if (rc)
			goto rollback_identity;
		rc = orig_setresuid(rp.uid, rp.uid, rp.uid);
		if (rc)
			goto rollback_identity;
		cred = prepare_creds();
		if (!cred) {
			ksu_put_group_info(prepared_groups);
			return -ENOMEM;
		}
	}

	raw = (char *)cred;
	*(u32 *)(raw + 4)  = rp.uid;
	*(u32 *)(raw + 8)  = rp.gid;
	*(u32 *)(raw + 12) = rp.uid;
	*(u32 *)(raw + 16) = rp.gid;
	*(u32 *)(raw + 20) = rp.uid;
	*(u32 *)(raw + 24) = rp.gid;
	*(u32 *)(raw + 28) = rp.uid;
	*(u32 *)(raw + 32) = rp.gid;
	*(u32 *)(raw + 36) = 0; /* securebits */
	*(u64 *)(raw + 40) = rp.capabilities.inheritable & cap_mask;
	*(u64 *)(raw + 48) = rp.capabilities.permitted & cap_mask;
	*(u64 *)(raw + 56) = rp.capabilities.effective & cap_mask;
	*(u64 *)(raw + 64) = rp.capabilities.permitted & cap_mask;
	*(u64 *)(raw + 72) = rp.capabilities.inheritable &
			      rp.capabilities.permitted &
			      rp.capabilities.effective & cap_mask;
	p_set_groups(cred, prepared_groups);
	ksu_put_group_info(prepared_groups);
	prepared_groups = NULL;
	commit_creds(cred);
	stat_profile_grant++;
	rc = ksu_set_domain(rp.selinux_domain);
	if (rc) {
		pr_warn("ksu_glue: profile context %s failed: %d\n",
			rp.selinux_domain, rc);
		return rc;
	}
	ksu_disable_seccomp();
	if ((rp.flags & FLAG_KSU_NO_NEW_PRIVS) && orig_prctl) {
		rc = orig_prctl(PR_SET_NO_NEW_PRIVS_OPT, 1, 0, 0, 0);
		if (rc) {
			pr_warn("ksu_glue: PR_SET_NO_NEW_PRIVS failed: %d\n", rc);
			return rc;
		}
	}
	rc = ksu_setup_mount_namespace(rp.namespaces);
	if (rc) {
		pr_warn("ksu_glue: profile namespace %d failed: %d\n",
			rp.namespaces, rc);
		return rc;
	}
	return 0;

rollback_identity:
	/* This is an expendable su child; drop all temporary init groups first. */
	if (orig_setgroups)
		orig_setgroups(0, NULL);
	if (orig_setresgid)
		orig_setresgid(source_gid, source_gid, source_gid);
	if (orig_setresuid)
		orig_setresuid(source_uid, source_uid, source_uid);
	ksu_put_group_info(prepared_groups);
	return rc;
}

static void ksu_umount_for_uid(uid_t uid)
{
	const struct cred *old;
	mm_segment_t oldfs;
	int i;

	if (!enable_kernel_umount || !storage_cred || uid < 10000 ||
	    !profile_should_umount(uid))
		return;
	old = override_creds(storage_cred);
	oldfs = get_fs();
	set_fs(KERNEL_DS);
	mutex_lock(&umount_lock);
	for (i = 0; i < KSU_MAX_UMOUNT_ENTRIES; i++) {
		long rc;
		if (!umount_entries[i].used)
			continue;
		rc = orig_umount((const char __user *)umount_entries[i].path,
				 umount_entries[i].flags);
		if (rc && rc != -EINVAL && rc != -ENOENT)
			stat_kernel_umount_fail++;
		else if (!rc)
			stat_kernel_umount++;
	}
	mutex_unlock(&umount_lock);
	set_fs(oldfs);
	revert_creds(old);
}

/* stash a small string just under the user SP (sucompat.c userspace_stack_buffer) */
static bool user_path_is_su(const char __user *ufn)
{
	char buf[sizeof(SU_PATH) + 1] = {0};
	if (strncpy_from_user(buf, ufn, sizeof(buf)) < 0) return false;
	return memcmp(buf, SU_PATH, sizeof(SU_PATH)) == 0;  /* includes NUL */
}

static bool user_argv_eq(const char __user *const __user *argv,
			 unsigned int index, const char *expected)
{
	const char __user *arg;
	char buf[32] = {0};
	long n;

	if (!argv || get_user(arg, argv + index) || !arg)
		return false;
	n = strncpy_from_user(buf, arg, sizeof(buf));
	if (n <= 0 || n >= sizeof(buf))
		return false;
	return !strcmp(buf, expected);
}

/*
 * SCR-01's property service rejects ctl.restart from shell:s0 even for uid 0
 * (bionic reports PROP_ERROR_HANDLE_CONTROL_MESSAGE). Upstream's Manager does
 * not check that result after toggling ADB Root. Scope the required init-domain
 * transition to the exact control command and to a trusted KSU root helper;
 * the credential is private to this short-lived setprop process.
 */
static void ksu_prepare_adbd_restart(const char *filename,
				     const char __user *const __user *argv)
{
	bool applet =
		user_argv_eq(argv, 0, "setprop") ||
		user_argv_eq(argv, 0, "/system/bin/setprop");
	bool direct = filename && !strcmp(filename, "/system/bin/setprop");
	bool toolbox =
		filename &&
		(!strcmp(filename, "/system/bin/toolbox") ||
		 !strcmp(filename, "/system/bin/toybox")) &&
		applet;

	/*
	 * On SCR-01 /system/bin/setprop is a symlink to toolbox. Android's shell
	 * can resolve that link before exec, or use execveat with a pathname that
	 * is not the public symlink. Accept the exact setprop argv[0] applet form
	 * in addition to the direct/toolbox paths, and still require the exact
	 * control arguments below.
	 */
	if ((!direct && !toolbox && !applet) ||
	    !user_argv_eq(argv, 1, "ctl.restart") ||
	    !user_argv_eq(argv, 2, "adbd"))
		return;
	if (!READ_ONCE(adb_restart_armed_until) ||
	    time_after(jiffies, READ_ONCE(adb_restart_armed_until)))
		return;

	ksu_set_domain("u:r:init:s0");
	ksu_resolve_sids();
	if (ksu_is_current_domain(init_sid_cache)) {
		WRITE_ONCE(adb_restart_armed_until, 0);
		stat_adb_restart++;
	} else {
		stat_adb_restart_fail++;
	}
}

/*
 * Android's persistent libsu shell may execute the setprop toolbox applet
 * without a separately observable exec syscall. The feature setter above
 * therefore arms a five-second, one-shot token. Consume it only when UID 0
 * connects to Android's exact property-service socket. The domain is changed
 * before connect so init sees init:s0 on the peer socket; the subsequent
 * command still carries the Manager's exact ctl.restart/adbd strings.
 */
static long hook_connect(int fd, struct sockaddr __user *uaddr, int addrlen)
{
	struct sockaddr_un addr;
	unsigned long armed = READ_ONCE(adb_restart_armed_until);

	if (armed && !time_after(jiffies, armed) && dev_uid() == 0 &&
	    uaddr && addrlen >= offsetof(struct sockaddr_un, sun_path) + 1) {
		int copy_len = min_t(int, addrlen, sizeof(addr));

		memset(&addr, 0, sizeof(addr));
		if (!copy_from_user(&addr, uaddr, copy_len) &&
		    addr.sun_family == AF_UNIX &&
		    !strcmp(addr.sun_path, "/dev/socket/property_service")) {
			ksu_set_domain("u:r:init:s0");
			ksu_resolve_sids();
			if (ksu_is_current_domain(init_sid_cache)) {
				WRITE_ONCE(adb_restart_armed_until, 0);
				stat_adb_restart++;
			} else {
				stat_adb_restart_fail++;
			}
		}
	}
	return orig_connect(fd, uaddr, addrlen);
}

static long hook_reboot(int magic1, int magic2, unsigned int cmd, void __user *arg)
{
	/* Stage-1 bootstrap: install the ksu anon fd */
	if ((u32)magic1 == KSU_INSTALL_MAGIC1 && (u32)magic2 == KSU_INSTALL_MAGIC2) {
		int r = ksu_install_fd((int __user *)arg, true);
		return r ? r : 0;
	}
	/* Root-only toolkit selector: crown manager (replaces throne_tracker) */
	if ((u32)magic2 == CHANGE_MANAGER_UID) {
		if (dev_uid() != 0) return 0;
		ksu_set_manager_appid(cmd);           /* cmd = appid */
		return 0;
	}
	return orig_reboot(magic1, magic2, cmd, arg);
}

static long hook_faccessat(int dfd, const char __user *filename, int mode)
{
	if (enable_sucompat && allowed_for_su() && filename && user_path_is_su(filename))
		return 0;   /* pretend /system/bin/su exists & is accessible */
	return orig_faccessat(dfd, filename, mode);
}
static long hook_newfstatat(int dfd, const char __user *filename, void __user *st, int flag)
{
	if (enable_sucompat && allowed_for_su() && filename && st && user_path_is_su(filename)) {
		/* Fake a regular, world-exec file so shells accept /system/bin/su as a
		 * command (arm64 struct stat = 128 bytes; st_mode@16, st_nlink@20, st_size@48). */
		unsigned char b[128];
		memset(b, 0, sizeof(b));
		*(u32 *)(b + 16) = 0100755;   /* S_IFREG | 0755 */
		*(u32 *)(b + 20) = 1;         /* st_nlink */
		*(u64 *)(b + 48) = 32768;     /* st_size  */
		if (copy_to_user(st, b, sizeof(b)) == 0)
			return 0;
	}
	return orig_newfstatat(dfd, filename, st, flag);
}
static long hook_execve(const char __user *filename,
			const char __user *const __user *argv,
			const char __user *const __user *envp)
{
	const char __user *const __user *exec_envp = envp;

	if (filename) {
		char b[160] = {0};
		long n = strncpy_from_user(b, filename, sizeof(b) - 1);
		if (n > 0) {
			bool root_helper = ksu_is_root_helper();
			/*
			 * UID 0 is the authority boundary here; Manager command shells
			 * can briefly retain a non-shell SID across process spawning.
			 * ksu_prepare_adbd_restart still requires the exact setprop
			 * applet and exact ctl.restart/adbd argument pair.
			 */
			if (dev_uid() == 0)
				ksu_prepare_adbd_restart(b, argv);
			if (enable_adb_root && n >= 5 &&
			    !memcmp(b + n - 5, "/adbd", 5) &&
			    ksu_adb_root_begin_exec()) {
				bool prepared = false;

				exec_envp = ksu_prepare_adb_env(envp, &prepared);
				if (!prepared) {
					WRITE_ONCE(enable_adb_root, false);
					stat_adb_root_recover++;
				}
			}
			if (root_helper)
				sulog_emit(KSU_SULOG_ROOT_EXECVE, 0, dev_uid(),
					   dev_euid(), b);
			/*
			 * Android's ProcessImpl closes inherited Java-side descriptors
			 * while spawning libksud. Seed a fresh non-CLOEXEC driver fd in
			 * the manager child immediately before its exec, after all Java
			 * descriptor sanitization has finished.
			 */
			if (enable_manager_fd && is_manager()) {
				ksu_ensure_fd(false);
				stat_manager_exec_fd++;
				/*
				 * This hook runs in the forked ProcessBuilder child, not the
				 * manager UI parent. Keep the UI's Android filter, but let its
				 * signed/crowned KSU helper reach the reboot supercall if the
				 * inherited driver-fd probe falls back.
				 */
				ksu_disable_seccomp();
				stat_manager_child_seccomp++;
			}
			/*
			 * The first APK bootstrap becomes uid 0 through the planted hook,
			 * not KSU's GRANT_ROOT ioctl, and therefore starts without a
			 * [ksu_driver] fd. Give root-only exec children the same verified
			 * transport as the manager. libksud then uses ioctl instead of its
			 * reboot(2) fallback, which inherited app seccomp kills before our
			 * syscall-table hook can see it.
			 */
			if (enable_manager_fd && dev_uid() == 0) {
				/*
				 * A task rooted by the temporary sel_read_enforce payload still
				 * owns its original untrusted_app cred/security blob. Replace it
				 * with a private init cred once, then select shell:s0. Apart from
				 * restoring binder/proc access, this lets libksud enumerate the
				 * inherited [ksu_driver] fds instead of taking reboot(2).
				 */
				if (!root_helper) {
					long refresh_rc = ksu_escalate_current();
					if (refresh_rc)
						return refresh_rc;
					root_helper = true;
					stat_root_domain_refresh++;
				}
				ksu_ensure_fd(false);
				stat_root_exec_fd++;
				ksu_disable_seccomp();
			}
			/* sucompat: /system/bin/su -> escalate + exec ksud */
			if (enable_sucompat && memcmp(b, SU_PATH, sizeof(SU_PATH)) == 0 &&
			    allowed_for_su()) {
				struct file *f;
				int fd;
				long escalate_rc;
				sulog_emit(KSU_SULOG_SUCOMPAT, 0, dev_uid(),
					   dev_euid(), SU_PATH);
				fd = get_unused_fd_flags(O_CLOEXEC);
				if (fd >= 0) {
					f = ksu_open_userspace_helper();
					if (!IS_ERR(f)) {
						long rc;
						const char __user *empty;
						escalate_rc = ksu_escalate_current();
						if (escalate_rc) {
							fput(f);
							put_unused_fd(fd);
							return escalate_rc;
						}
						ksu_disable_seccomp();
						/* AT_EMPTY_PATH empty string = NUL at end of the su path */
						empty = filename + (sizeof(SU_PATH) - 1);
						fd_install(fd, f);
						rc = orig_execveat(fd, empty, argv, envp, 0x1000);
						/*
						 * Successful exec may rebuild arm64 thread flags from
						 * current->seccomp. Clear again after the new image is
						 * committed, before returning to userspace.
						 */
						if (rc == 0)
							ksu_disable_seccomp();
						return rc;
					}
					put_unused_fd(fd);
				}
				/* Leave the original exec untouched if the trusted helper is unavailable. */
			}
		}
	}
	{
		long rc = orig_execve(filename, argv, exec_envp);
		if (rc == 0 && ksu_is_root_helper())
			ksu_disable_seccomp();
		return rc;
	}
}
/* execveat path (bionic posix_spawn/fexecve use this): root helpers only. */
static long hook_execveat(int dfd, const char __user *filename,
			  const char __user *const __user *argv,
			  const char __user *const __user *envp, int flags)
{
	/*
	 * libsu/bionic may spawn toolbox applets with execveat rather than execve.
	 * Inspect argv even when filename is empty (AT_EMPTY_PATH); the restart
	 * helper accepts only UID 0 and the exact setprop control command.
	 */
	if (dev_uid() == 0) {
		char b[160] = {0};
		long path_len = filename ?
			strncpy_from_user(b, filename, sizeof(b) - 1) : -EFAULT;

		ksu_prepare_adbd_restart(path_len > 0 ? b : NULL, argv);
	}
	if (enable_manager_fd) {
		bool root_helper = ksu_is_root_helper();
		long n = filename ? strnlen_user(filename, 2) : -EFAULT;

		/*
		 * bionic's fexecve/posix_spawn enters with filename="" and
		 * AT_EMPTY_PATH. Identity, not a non-empty pathname, is the security
		 * gate; seed the fd after descriptor sanitization in both forms.
		 */
		if (root_helper && n == 1)
			stat_execveat_empty_root++;
		if (is_manager()) {
			ksu_ensure_fd(false);
			stat_manager_exec_fd++;
			/* fexecve/posix_spawn child of the crowned manager; see execve. */
			ksu_disable_seccomp();
			stat_manager_child_seccomp++;
		}
		if (dev_uid() == 0) {
			if (!root_helper) {
				long refresh_rc = ksu_escalate_current();
				if (refresh_rc)
					return refresh_rc;
				root_helper = true;
				stat_root_domain_refresh++;
			}
			ksu_ensure_fd(false);
			stat_root_exec_fd++;
			ksu_disable_seccomp();
		}
	}
	{
		long rc = orig_execveat(dfd, filename, argv, envp, flags);
		if (rc == 0 && ksu_is_root_helper())
			ksu_disable_seccomp();
		return rc;
	}
}
/* setresuid: KSU-Next's manager-fd install point. Zygote forks an app, sanitizes fds,
 * then the app setresuid()s to its own uid. We run AFTER that, so installing an
 * [ksu_driver] fd here lands in the app's own table (never in zygote's). Only the
 * crowned manager gets it; keeping it across exec lets libksud avoid a seccomp-blocked
 * reboot supercall. */
static long hook_setresuid(uid_t ruid, uid_t euid, uid_t suid)
{
	bool zygote_child, adbd;
	long rc;

	ksu_resolve_sids();
	zygote_child = ksu_is_current_domain(zygote_sid_cache);
	adbd = enable_adb_root && ksu_is_current_domain(adbd_sid_cache);
	if (adbd &&
	    ((ruid != (uid_t)-1 && ruid != 0) ||
	     (euid != (uid_t)-1 && euid != 0) ||
	     (suid != (uid_t)-1 && suid != 0))) {
		stat_adb_root_block++;
		return 0;
	}
	rc = orig_setresuid(ruid, euid, suid);
	if (rc == 0 && enable_manager_fd && ksu_manager_appid != (uid_t)-1) {
		uid_t nu = (ruid != (uid_t)-1) ? ruid : euid;
		if (nu != (uid_t)-1 && (nu % KSU_PER_USER_RANGE) == ksu_manager_appid) {
			/*
			 * Keep this manager-only descriptor across exec. libksud helpers
			 * inherit it and therefore never need the reboot supercall that
			 * Android's normal seccomp profile blocks.
			 */
			ksu_ensure_fd(false);
		}
	}
	if (rc == 0 && zygote_child)
		ksu_umount_for_uid(dev_uid());
	return rc;
}

static long hook_setuid(uid_t uid)
{
	ksu_resolve_sids();
	if (enable_adb_root && uid != 0 &&
	    ksu_is_current_domain(adbd_sid_cache)) {
		stat_adb_root_block++;
		return 0;
	}
	return orig_setuid(uid);
}

static long hook_setgid(gid_t gid)
{
	ksu_resolve_sids();
	if (enable_adb_root && gid != 0 &&
	    ksu_is_current_domain(adbd_sid_cache)) {
		stat_adb_root_block++;
		return 0;
	}
	return orig_setgid(gid);
}

static long hook_setresgid(gid_t rgid, gid_t egid, gid_t sgid)
{
	ksu_resolve_sids();
	if (enable_adb_root && ksu_is_current_domain(adbd_sid_cache) &&
	    ((rgid != (gid_t)-1 && rgid != 0) ||
	     (egid != (gid_t)-1 && egid != 0) ||
	     (sgid != (gid_t)-1 && sgid != 0))) {
		stat_adb_root_block++;
		return 0;
	}
	return orig_setresgid(rgid, egid, sgid);
}

static long hook_setgroups(int count, const gid_t __user *groups)
{
	ksu_resolve_sids();
	if (enable_adb_root && ksu_is_current_domain(adbd_sid_cache)) {
		stat_adb_root_block++;
		return 0;
	}
	return orig_setgroups(count, groups);
}

/* app_process (libsu's RootServerMain) re-arms a seccomp filter during ART startup —
 * AFTER our exec-time clear — then SIGSYSes on setgid/reboot. Refuse the re-arm for
 * su-spawned root helpers: no-op the seccomp(2) syscall and prctl(PR_SET_SECCOMP) when
 * running as root, so the filter is never installed and privileged syscalls pass. */
static long hook_seccomp(long op, long flags, long uargs, long a4, long a5)
{
	if (enable_manager_fd && ksu_is_root_helper()) {
		stat_seccomp_bypass++;
		ksu_disable_seccomp();
		return 0;
	}
	return orig_seccomp(op, flags, uargs, a4, a5);
}

/* Optional Tier-0 shortcut retained for bring-up: prctl(0x4B535501,1) -> instant root */
#define KSU_PRCTL_MAGIC 0x4B535501UL
static long hook_prctl(long option, long a2, long a3, long a4, long a5)
{
	if ((unsigned long)option == KSU_PRCTL_MAGIC && a2 == 1) {
		uid_t uid = dev_uid();
		uid_t appid = uid % KSU_PER_USER_RANGE;
		struct cred *n;
		if (!enable_dev_prctl ||
		    !(uid == 0 || uid == 2000 || is_manager() ||
		      appid == bootstrap_appid))
			return -EPERM;
		n = prepare_kernel_cred(NULL);
		if (n) {
			commit_creds(n);
			ksu_disable_seccomp();
			ksu_set_shell_domain();
		}
		return 0;
	}
	if (enable_manager_fd && option == PR_SET_SECCOMP_OPT &&
	    ksu_is_root_helper()) {
		stat_prctl_bypass++;
		ksu_disable_seccomp();
		return 0;   /* swallow seccomp install for root helpers */
	}
	return orig_prctl(option, a2, a3, a4, a5);
}

/* ------------------------------------------------------------------ *
 * 6. vmap RW-alias syscall-table patch (proven ksuhook.c primitive)
 * ------------------------------------------------------------------ */
static int patch_sct_slot(int nr, unsigned long val, unsigned long *saved)
{
	struct page *pg = pfn_to_page(sct_phys >> PAGE_SHIFT);
	unsigned long *w = vmap(&pg, 1, VM_MAP, PAGE_KERNEL);
	if (!w) return -ENOMEM;
	if (saved) *saved = ((unsigned long *)sct_va)[nr];
	w[nr] = val;
	vunmap(w);
	smp_wmb();     /* publish slot before other CPUs read it */
	return 0;
}

/*
 * The exploit temporarily replaces sel_read_enforce with a 72-byte uid/cap
 * bootstrap. Once this module is live and the manager is crowned, retaining
 * that system-wide read-trigger is unnecessary and makes getenforce(8) see
 * EOF ("unknown"). Restore the exact stock SCR01KDU1AVK2 prologue through a
 * RW vmap alias. Refuse to touch the page unless it contains either our known
 * bootstrap or the exact stock bytes.
 */
#define SEL_READ_ENFORCE_VA   0xffffff8008468730UL
#define SEL_READ_ENFORCE_PHYS 0x40468730UL
static const u32 sel_read_stock[] = {
	0xd10143ff, 0xa9027bfd, 0xf9001bf5, 0xa9044ff4,
	0x910083fd, 0xb000b728, 0xf9456508, 0xaa0203f4,
	0xf00099c2, 0xaa0303f3, 0xf81f83a8, 0x9000cf28,
	0xb94e4108, 0xaa0103f5, 0x91176442, 0x910033e0,
	0x7100011f, 0x1a9f07e3,
};
static const u32 sel_read_bootstrap[] = {
	0xd5384100, 0xf9400001, 0x9274f821, 0xf9000001,
	0xf943c800, 0x2900fc1f, 0x2901fc1f, 0x2902fc1f,
	0x2903fc1f, 0x12800001, 0x528007e2, 0x29050801,
	0x29060801, 0x29070801, 0x29080801, 0x29090801,
	0xd2800000, 0xd65f03c0,
};
#define VERSION_PROC_SHOW_VA   0xffffff8008310618UL
#define VERSION_PROC_SHOW_PHYS 0x40310618UL
/* Exact 72 bytes from SCR01KDU1AVK2 Image at offset 0x290618.  The
 * version_proc_show body is 56 bytes; include the first 16 bytes of the
 * adjacent function because the 72-byte temporary bootstrap spans both. */
static const u32 version_proc_stock[] = {
	0xa9bf7bfd, 0x910003fd, 0xd5384108, 0xf943e108,
	0x90006f81, 0x91055c21, 0xf9400508, 0x91001102,
	0x91021903, 0x91031d04, 0x97fe5683, 0x2a1f03e0,
	0xa8c17bfd, 0xd65f03c0, 0xa9bf7bfd, 0x910003fd,
	0xaa0103e0, 0x90000001,
};
static bool sel_read_restored;

static int restore_version_proc_show(void)
{
	const void *live = (const void *)VERSION_PROC_SHOW_VA;
	unsigned long page_phys = VERSION_PROC_SHOW_PHYS & PAGE_MASK;
	unsigned long offset = VERSION_PROC_SHOW_PHYS & ~PAGE_MASK;
	struct page *pg;
	char *rw;

	if (!memcmp(live, version_proc_stock, sizeof(version_proc_stock)))
		return 0;
	if (memcmp(live, sel_read_bootstrap, sizeof(sel_read_bootstrap))) {
		pr_err("ksu_glue: refuse proc/version restore: unexpected live bytes\n");
		return -EINVAL;
	}
	pg = pfn_to_page(page_phys >> PAGE_SHIFT);
	rw = vmap(&pg, 1, VM_MAP, PAGE_KERNEL);
	if (!rw)
		return -ENOMEM;
	memcpy(rw + offset, version_proc_stock, sizeof(version_proc_stock));
	smp_wmb();
	flush_icache_range((unsigned long)rw + offset,
			   (unsigned long)rw + offset + sizeof(version_proc_stock));
	flush_icache_range(VERSION_PROC_SHOW_VA,
			   VERSION_PROC_SHOW_VA + sizeof(version_proc_stock));
	vunmap(rw);
	if (memcmp(live, version_proc_stock, sizeof(version_proc_stock)))
		return -EIO;
	pr_info("ksu_glue: stock version_proc_show restored\n");
	return 0;
}

static int restore_sel_read_enforce(void)
{
	const void *live = (const void *)SEL_READ_ENFORCE_VA;
	unsigned long page_phys = SEL_READ_ENFORCE_PHYS & PAGE_MASK;
	unsigned long offset = SEL_READ_ENFORCE_PHYS & ~PAGE_MASK;
	struct page *pg;
	char *rw;
	int rc;

	rc = restore_version_proc_show();
	if (rc)
		return rc;

	if (!memcmp(live, sel_read_stock, sizeof(sel_read_stock))) {
		sel_read_restored = true;
		return 0;
	}
	if (memcmp(live, sel_read_bootstrap, sizeof(sel_read_bootstrap))) {
		pr_err("ksu_glue: refuse sel_read restore: unexpected live bytes\n");
		return -EINVAL;
	}

	pg = pfn_to_page(page_phys >> PAGE_SHIFT);
	rw = vmap(&pg, 1, VM_MAP, PAGE_KERNEL);
	if (!rw)
		return -ENOMEM;
	memcpy(rw + offset, sel_read_stock, sizeof(sel_read_stock));
	smp_wmb();
	/* Clean through the writable alias and invalidate the executable alias. */
	flush_icache_range((unsigned long)rw + offset,
			   (unsigned long)rw + offset + sizeof(sel_read_stock));
	flush_icache_range(SEL_READ_ENFORCE_VA,
			   SEL_READ_ENFORCE_VA + sizeof(sel_read_stock));
	vunmap(rw);

	if (memcmp(live, sel_read_stock, sizeof(sel_read_stock))) {
		pr_err("ksu_glue: sel_read restore verification failed\n");
		return -EIO;
	}
	sel_read_restored = true;
	pr_info("ksu_glue: stock sel_read_enforce restored\n");
	return 0;
}

static int restore_sel_set(const char *val, const struct kernel_param *kp)
{
	bool requested;
	int rc = kstrtobool(val, &requested);
	if (rc)
		return rc;
	if (!requested)
		return -EINVAL;
	return restore_sel_read_enforce();
}
static const struct kernel_param_ops restore_sel_ops = {
	.set = restore_sel_set,
	.get = param_get_bool,
};
module_param_cb(restore_sel_read_enforce, &restore_sel_ops,
		&sel_read_restored, 0644);

/* ------------------------------------------------------------------ *
 * 7. init / exit
 * ------------------------------------------------------------------ */
static void resolve_symbols(void)
{
	unsigned long (*kln)(const char *) = (void *)kln_addr;
	if (secctx2secid_addr)
		p_secctx_to_secid = (void *)secctx2secid_addr;
	else if (kln)
		p_secctx_to_secid = (void *)kln("security_secctx_to_secid");
	if (kln) {
		p_vfs_fsync = (void *)kln("vfs_fsync");
		p_groups_alloc = (void *)kln("groups_alloc");
		p_groups_free = (void *)kln("groups_free");
		p_groups_sort = (void *)kln("groups_sort");
		p_set_groups = (void *)kln("set_groups");
	}
}

static int __init ksu_glue_init(void)
{
	unsigned long *sct = (unsigned long *)sct_va;
	resolve_symbols();

	orig_reboot     = (fn_reboot_t)     sct[NR_REBOOT];
	orig_fcntl      = (void *)           sct[NR_FCNTL];
	orig_faccessat  = (fn_faccessat_t)  sct[NR_FACCESSAT];
	orig_newfstatat = (fn_newfstatat_t) sct[NR_NEWFSTATAT];
	orig_execve     = (fn_execve_t)     sct[NR_EXECVE];
	orig_execveat   = (fn_execveat_t)   sct[NR_EXECVEAT];
	orig_connect    = (fn_connect_t)    sct[NR_CONNECT];
	orig_umount     = (fn_umount_t)     sct[NR_UMOUNT];
	orig_mount      = (fn_mount_t)      sct[NR_MOUNT];
	orig_close      = (fn_close_t)      sct[NR_CLOSE];
	orig_unshare    = (fn_unshare_t)    sct[NR_UNSHARE];
	orig_setns      = (fn_setns_t)      sct[NR_SETNS];
	orig_setuid     = (fn_setuid_t)     sct[NR_SETUID];
	orig_setgid     = (fn_setgid_t)     sct[NR_SETGID];
	orig_setresuid  = (fn_setresuid_t)  sct[NR_SETRESUID];
	orig_setresgid  = (fn_setresgid_t)  sct[NR_SETRESGID];
	orig_setpgid    = (void *)           sct[NR_SETPGID];
	orig_setgroups  = (fn_setgroups_t)  sct[NR_SETGROUPS];
	orig_getpid     = (void *)           sct[NR_GETPID];
	orig_getppid    = (void *)           sct[NR_GETPPID];
	orig_gettid     = (void *)           sct[NR_GETTID];
	orig_prctl      = (sysfn5_t)        sct[NR_PRCTL];
	orig_seccomp    = (sysfn5_t)        sct[NR_SECCOMP];
	orig_renameat2  = (void *)          sct[NR_RENAMEAT2];

	storage_cred = prepare_kernel_cred(NULL);
	if (!storage_cred)
		return -ENOMEM;
	profile_load();

	patch_sct_slot(NR_REBOOT,     (unsigned long)hook_reboot,     NULL);
	patch_sct_slot(NR_FACCESSAT,  (unsigned long)hook_faccessat,  NULL);
	patch_sct_slot(NR_NEWFSTATAT, (unsigned long)hook_newfstatat, NULL);
	patch_sct_slot(NR_EXECVE,     (unsigned long)hook_execve,     NULL);
	patch_sct_slot(NR_EXECVEAT,   (unsigned long)hook_execveat,   NULL);
	patch_sct_slot(NR_CONNECT,    (unsigned long)hook_connect,    NULL);
	patch_sct_slot(NR_SETUID,     (unsigned long)hook_setuid,     NULL);
	patch_sct_slot(NR_SETGID,     (unsigned long)hook_setgid,     NULL);
	patch_sct_slot(NR_SETRESUID,  (unsigned long)hook_setresuid,  NULL);
	patch_sct_slot(NR_SETRESGID,  (unsigned long)hook_setresgid,  NULL);
	patch_sct_slot(NR_SETGROUPS,  (unsigned long)hook_setgroups,  NULL);
	patch_sct_slot(NR_PRCTL,      (unsigned long)hook_prctl,      NULL);
	patch_sct_slot(NR_SECCOMP,    (unsigned long)hook_seccomp,    NULL);

	pr_info("ksu_glue: max-support hooks installed; profiles=%lu "
		"secctx2secid=%pS groups=%pS/%pS\n",
		stat_profile_load, p_secctx_to_secid, p_groups_alloc, p_set_groups);
	return 0;
}
static void __exit ksu_glue_exit(void)
{
	patch_sct_slot(NR_REBOOT,     (unsigned long)orig_reboot,     NULL);
	patch_sct_slot(NR_FACCESSAT,  (unsigned long)orig_faccessat,  NULL);
	patch_sct_slot(NR_NEWFSTATAT, (unsigned long)orig_newfstatat, NULL);
	patch_sct_slot(NR_EXECVE,     (unsigned long)orig_execve,     NULL);
	patch_sct_slot(NR_EXECVEAT,   (unsigned long)orig_execveat,   NULL);
	patch_sct_slot(NR_CONNECT,    (unsigned long)orig_connect,    NULL);
	patch_sct_slot(NR_SETUID,     (unsigned long)orig_setuid,     NULL);
	patch_sct_slot(NR_SETGID,     (unsigned long)orig_setgid,     NULL);
	patch_sct_slot(NR_SETRESUID,  (unsigned long)orig_setresuid,  NULL);
	patch_sct_slot(NR_SETRESGID,  (unsigned long)orig_setresgid,  NULL);
	patch_sct_slot(NR_SETGROUPS,  (unsigned long)orig_setgroups,  NULL);
	patch_sct_slot(NR_PRCTL,      (unsigned long)orig_prctl,      NULL);
	patch_sct_slot(NR_SECCOMP,    (unsigned long)orig_seccomp,    NULL);
	sulog_destroy();
	if (storage_cred) {
		put_cred(storage_cred);
		storage_cred = NULL;
	}
	pr_info("ksu_glue: unloaded, syscall table restored\n");
}
module_init(ksu_glue_init);
module_exit(ksu_glue_exit);
MODULE_LICENSE("GPL");
MODULE_AUTHOR("scr01");
MODULE_DESCRIPTION("KernelSU-Next runtime front-end (4.14 non-GKI, syscall-table hooks)");

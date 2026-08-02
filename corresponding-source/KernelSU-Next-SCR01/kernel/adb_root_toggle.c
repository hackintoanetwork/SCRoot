// SPDX-License-Identifier: GPL-2.0
/*
 * Hardware-test helper for an already loaded SCR-01 ksu_glue module.
 *
 * RC2 made enable_adb_root private and its feature setter rolled the flag back
 * when an unnecessary kernel usermode-helper restart failed. RC3 removes that
 * restart, matching upstream KSU-Next. This tiny module lets the unchanged
 * RC2 adbd exec/credential path be exercised without hot-unloading syscall
 * hooks or modifying the persistent boot image. The target address must be
 * derived from the exact loaded ELF's .bss symbol offset and live section base.
 */
#include <linux/init.h>
#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/types.h>

static unsigned long target_addr;
static unsigned long kln_addr = 0xffffff800817a8f0UL;
static long target_delta = 0x55a0;
static bool requested_state;

module_param(target_addr, ulong, 0400);
module_param(kln_addr, ulong, 0400);
module_param(target_delta, long, 0400);
module_param(requested_state, bool, 0400);

static int __init adb_root_toggle_init(void)
{
	bool *target;
	unsigned long (*kln)(const char *);
	unsigned long anchor;

	if (!target_addr) {
		kln = (void *)kln_addr;
		if (!kln)
			return -EINVAL;
		anchor = kln("ksu_dispatch_ioctl");
		if (anchor < 0xffffff8000000000UL)
			return -ENOENT;
		target_addr = anchor + target_delta;
	}
	if (target_addr < 0xffffff8000000000UL)
		return -EINVAL;

	target = (bool *)target_addr;
	WRITE_ONCE(*target, requested_state);
	smp_mb();
	if (READ_ONCE(*target) != requested_state)
		return -EIO;

	pr_info("adb_root_toggle: target=%px state=%d\n",
		target, requested_state);
	return 0;
}

static void __exit adb_root_toggle_exit(void)
{
	pr_info("adb_root_toggle: unloaded (target state retained)\n");
}

module_init(adb_root_toggle_init);
module_exit(adb_root_toggle_exit);
MODULE_LICENSE("GPL");
MODULE_AUTHOR("scr01");
MODULE_DESCRIPTION("SCR-01 one-shot ADB-root hardware test toggle");

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GLUE = ROOT / "corresponding-source" / "KernelSU-Next-SCR01" / "kernel" / "ksu_glue.c"
FLOW = ROOT / "app" / "src" / "main" / "java" / "com" / "scr01" / "scroot" / "RootFlow.kt"


class KsuGlueFailClosedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.glue = GLUE.read_text(encoding="utf-8")
        cls.flow = FLOW.read_text(encoding="utf-8")

    def test_all_syscall_hooks_are_one_transaction(self):
        table = re.search(
            r"static struct sct_hook_patch sct_hooks\[\] = \{(.*?)\n\};",
            self.glue,
            re.S,
        )
        self.assertIsNotNone(table)
        self.assertEqual(table.group(1).count("{ NR_"), 13)
        self.assertNotIn("patch_sct_slot", self.glue)
        self.assertIn("install_sct_hooks_transaction()", self.glue)
        self.assertIn("restore_sct_hooks_transaction()", self.glue)
        self.assertIn("#include <linux/stop_machine.h>", self.glue)
        self.assertIn(
            "stop_machine(sct_transaction_stop_machine, &tx, NULL)",
            self.glue,
        )

    def test_transaction_prevalidates_commits_and_reads_back_every_slot(self):
        start = self.glue.index("static int sct_transaction_stop_machine")
        end = self.glue.index("static int install_sct_hooks_transaction", start)
        body = self.glue[start:end]
        prevalidate = body.index("sct_transaction_values_match(tx, old_replacements)")
        first_write = body.index("WRITE_ONCE", prevalidate)
        verify = body.index("sct_transaction_values_match(tx, new_replacements)", first_write)
        rollback = body.index("rollback:", verify)
        rollback_verify = body.index("sct_transaction_values_match(tx, old_replacements)", rollback)
        self.assertLess(prevalidate, first_write)
        self.assertLess(first_write, verify)
        self.assertLess(rollback, rollback_verify)
        self.assertIn("tx->recovered_forward = true", body)
        self.assertIn("tx->degraded = true", body)

    def test_fault_injection_is_compile_time_only_and_rolls_back(self):
        self.assertGreaterEqual(self.glue.count("#ifdef SCR01_GLUE_FAULT_INJECT"), 2)
        self.assertIn("fault_after_hook_writes == i + 1", self.glue)
        injected = self.glue.index("fault_after_hook_writes == i + 1")
        rollback = self.glue.index("rollback:", injected)
        self.assertLess(injected, rollback)

    def test_production_build_has_no_unload_entrypoint(self):
        start = self.glue.index("static int restore_sct_hooks_transaction")
        end = self.glue.index("#define SEL_READ_ENFORCE_VA", start)
        body = self.glue[start:end]
        self.assertIn("stop_machine(sct_transaction_stop_machine, &tx, NULL)", body)
        exit_start = self.glue.index("static void __exit ksu_glue_exit")
        module_exit = self.glue.index("module_exit(ksu_glue_exit)", exit_start)
        guard = self.glue.rfind("#ifdef SCR01_GLUE_FAULT_INJECT", 0, exit_start)
        self.assertGreater(guard, self.glue.index("return 0;", exit_start - 1200))
        self.assertLess(guard, exit_start)
        self.assertLess(exit_start, module_exit)
        self.assertNotIn("panic(", self.glue)
        self.assertIn('MODULE_INFO(scr01_unload_policy, "blocked-in-production")', self.glue)

    def test_userspace_rejects_degraded_hook_transaction(self):
        self.assertIn("hook_transaction_degraded", self.flow)
        self.assertGreaterEqual(self.flow.count("HOOK_DEGRADED"), 8)
        self.assertIn("exit 44", self.flow)

    def test_direct_kernel_patch_receipt_precedes_syscall_hook_commit(self):
        validate = self.glue.index("static int validate_exploit_patch_receipt")
        init = self.glue.index("static int __init ksu_glue_init")
        init_end = self.glue.index("static void __exit ksu_glue_exit", init)
        body = self.glue[init:init_end]
        receipt = body.index("validate_exploit_patch_receipt()")
        hooks = body.index("install_sct_hooks_transaction()")
        self.assertLess(receipt, hooks)
        self.assertIn("patch_receipt_valid = false", self.glue[validate:init])
        self.assertIn("patch_receipt_valid = true", self.glue[validate:init])
        for target in (
            "SCR01_DEFEX_DISABLED_VA",
            "SCR01_SELINUX_ENFORCING_VA",
            "SCR01_AVC_PATCH_VA",
            "SCR01_AVC_PATCH2_VA",
            "SCR01_AVC_PATCH3_VA",
            "VERSION_PROC_SHOW_VA",
            "SEL_READ_ENFORCE_VA",
        ):
            self.assertIn(target, self.glue[validate:init])

    def test_direct_kernel_reads_follow_target_identity_check(self):
        start = self.glue.index("static int validate_exploit_patch_receipt")
        end = self.glue.index("static bool sel_read_restored", start)
        body = self.glue[start:end]
        identity = body.index("sct_va != SCR01_EXPECTED_SCT_VA")
        identity_return = body.index("return -EINVAL;", identity)
        first_read = body.index("defex = READ_ONCE", identity_return)
        first_hook_compare = body.index("memcmp((const void *)VERSION_PROC_SHOW_VA")
        self.assertLess(identity, identity_return)
        self.assertLess(identity_return, first_read)
        self.assertLess(first_read, first_hook_compare)

    def test_init_failure_cleans_profile_log_and_credential_state(self):
        start = self.glue.index("rc = install_sct_hooks_transaction()")
        end = self.glue.index("return 0;", start)
        body = self.glue[start:end]
        self.assertIn("profile_clear_memory()", body)
        self.assertIn("sulog_destroy()", body)
        self.assertIn("put_cred(storage_cred)", body)
        self.assertIn("storage_cred = NULL", body)

    def test_apk_requires_receipt_in_all_module_setup_paths(self):
        self.assertGreaterEqual(self.flow.count("patch_receipt_valid"), 4)
        self.assertIn('moduleLoaded = patchReceiptReady', self.flow)
        self.assertIn('val mod = isModuleLoaded() && patchReceiptReady', self.flow)
        self.assertIn('\\"\\$PATCH_RECEIPT\\" = Y ] || exit 43', self.flow)
        self.assertIn('else CROWN=43; fi;', self.flow)


if __name__ == "__main__":
    unittest.main()

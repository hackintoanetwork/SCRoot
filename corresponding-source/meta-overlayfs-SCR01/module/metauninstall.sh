#!/system/bin/sh

# Direct mode has no shared image content to remove. Clear only the exact
# partition entries owned by this metamodule so a same-boot uninstall cannot
# leave stale per-app unmount targets in the runtime module.
for mnt in /system /system_ext /vendor /product /odm /oem; do
    /data/adb/ksud kernel umount del "$mnt" >/dev/null 2>&1 || true
done

exit 0

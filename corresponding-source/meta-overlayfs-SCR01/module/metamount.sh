#!/system/bin/sh

MODDIR="${0%/*}"
export MODULE_METADATA_DIR="/data/adb/modules"
export MODULE_CONTENT_DIR="/data/adb/modules"
KSUD="/data/adb/ksud"

echo "[meta-overlayfs-scr01] direct module scan"
if awk '
    {
        for (i = 6; i <= NF; i++) {
            if ($i == "-" && $(i + 1) == "overlay" &&
                $(i + 2) == "KSU") {
                found = 1
            }
        }
    }
    END { exit(found ? 0 : 1) }
' /proc/self/mountinfo; then
    # post-fs-data may be retried by recovery/controller flows. Stacking an
    # identical overlay each time leaks mounts and makes later teardown
    # ambiguous, while module changes are staged for the next boot anyway.
    echo "[meta-overlayfs-scr01] existing KSU overlay found; mount step skipped"
    mount_rc=0
else
    "$MODDIR/meta-overlayfs"
    mount_rc=$?
fi
register_rc=0

# KernelSU's per-app module isolation only processes mount points registered
# through KSU_IOCTL_ADD_TRY_UMOUNT. meta-overlayfs mounts the overlays but does
# not populate that list itself, so register only partitions that are actually
# backed by an OverlayFS mount whose source is the official "KSU" marker.
#
# Delete-before-add keeps repeated post-fs-data runs idempotent. MNT_DETACH is
# required because these mounts can be busy in a freshly forked app namespace.
for mnt in /system /system_ext /vendor /product /odm /oem; do
    if awk -v target="$mnt" '
        $5 == target {
            for (i = 6; i <= NF; i++) {
                if ($i == "-" && $(i + 1) == "overlay" &&
                    $(i + 2) == "KSU") {
                    found = 1
                }
            }
        }
        END { exit(found ? 0 : 1) }
    ' /proc/self/mountinfo; then
        "$KSUD" kernel umount del "$mnt" >/dev/null 2>&1
        if "$KSUD" kernel umount add -f 2 "$mnt"; then
            echo "[meta-overlayfs-scr01] registered kernel umount: $mnt"
        else
            echo "[meta-overlayfs-scr01] failed to register kernel umount: $mnt" >&2
            register_rc=1
        fi
    else
        # Drop stale entries when a partition no longer has a KSU overlay.
        "$KSUD" kernel umount del "$mnt" >/dev/null 2>&1 || true
    fi
done

[ "$mount_rc" -eq 0 ] || exit "$mount_rc"
exit "$register_rc"

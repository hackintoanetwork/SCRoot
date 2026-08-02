#!/system/bin/sh

ABI=$(grep_get_prop ro.product.cpu.abi)
[ "$ABI" = "arm64-v8a" ] || abort "! Unsupported architecture: $ABI"
[ -f "$MODPATH/meta-overlayfs-aarch64" ] ||
    abort "! Missing meta-overlayfs-aarch64"

mv "$MODPATH/meta-overlayfs-aarch64" "$MODPATH/meta-overlayfs" ||
    abort "! Failed to install mount helper"
chmod 0755 "$MODPATH/meta-overlayfs" ||
    abort "! Failed to set mount helper permissions"

ui_print "- SCR-01 direct /data OverlayFS backend selected"

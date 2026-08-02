#!/system/bin/sh

# SCR-01 rejects loop-device ioctls from the shell SELinux domain. Keep regular
# module payloads in their normal /data/adb/modules/<id> directories instead of
# relocating them into a loop-mounted ext4 image.
ui_print "- Using SCR-01 direct module installer"
install_module

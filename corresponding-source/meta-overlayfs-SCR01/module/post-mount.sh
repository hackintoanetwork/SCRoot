#!/system/bin/sh

# The stock KernelSU nuke-ext4-sysfs UAPI is unavailable in the runtime glue,
# and direct mode does not create a module ext4 mount to hide.
exit 0

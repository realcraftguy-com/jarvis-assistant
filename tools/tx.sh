#!/bin/sh
# Runs a command inside Termux on the phone (Termux GitHub build is debuggable → run-as works over USB).
# usage: tools/tx.sh 'pkg install -y proot-distro'
P=/data/data/com.termux/files
exec adb shell run-as com.termux $P/usr/bin/env -i HOME=$P/home PREFIX=$P/usr PATH=$P/usr/bin TMPDIR=$P/usr/tmp \
  LANG=en_US.UTF-8 TERM=xterm-256color LD_PRELOAD=$P/usr/lib/libtermux-exec-ld-preload.so $P/usr/bin/bash -lc "'$1'"

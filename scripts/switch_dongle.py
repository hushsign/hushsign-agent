#!/usr/bin/env python3
"""P1-S14 Windows helper: flip a Huawei 12d1:1520 (storage) dongle into
12d1:1465 (modem) mode via a control transfer, using libusb/pyusb.

Usage: python switch_dongle.py [vid] [pid]
"""
import ctypes
import sys
import time
from pathlib import Path

import usb.core
import usb.util

# 31-byte usb_modeswitch sequences for Huawei 1520 storage-mode dongles.
SEQUENCES = [
    bytes.fromhex("55534243123456780000000000000011062000000101000100000000000000"),
    bytes.fromhex("55534243123456780000000000000011060000000000000000000000000000"),
]


def libusb_backend():
    try:
        import libusb  # PyPI package bundles libusb-1.0.dll
    except ImportError:
        return None
    root = Path(libusb.__file__).parent
    candidates = [
        root / "_platform" / "windows" / "x86_64" / "libusb-1.0.dll",
        root / "_platform" / "windows" / "x86" / "libusb-1.0.dll",
        root / "_platform" / "windows" / "arm64" / "libusb-1.0.dll",
        root / "_platform" / "_windows" / "x64" / "libusb-1.0.dll",
        root / "_platform" / "_windows" / "x86" / "libusb-1.0.dll",
    ]
    for candidate in candidates:
        if candidate.exists():
            import usb.backend.libusb1
            return usb.backend.libusb1.get_backend(
                find_library=lambda x: str(candidate))
    return None


def main():
    vid = int(sys.argv[1], 16) if len(sys.argv) > 1 else 0x12D1
    pid = int(sys.argv[2], 16) if len(sys.argv) > 2 else 0x1520

    backend = libusb_backend()
    if backend is None:
        print("FAILED: no libusb backend — pip install pyusb libusb")
        sys.exit(2)

    dev = usb.core.find(idVendor=vid, idProduct=pid, backend=backend)
    if dev is None:
        print(f"FAILED: no USB device {vid:04X}:{pid:04X} present")
        sys.exit(2)

    try:
        if dev.is_kernel_driver_active(0):
            dev.detach_kernel_driver(0)
    except (usb.core.USBError, NotImplementedError):
        pass

    for i, seq in enumerate(SEQUENCES, 1):
        print(f"trying switch sequence {i} ({len(seq)} bytes)")
        try:
            dev.ctrl_transfer(
                bmRequestType=0x21, bRequest=0x01, wValue=0, wIndex=0,
                data_or_wLength=seq, timeout=5000)
            print("control transfer sent OK")
        except usb.core.USBError as e:
            print(f"control transfer error (expected when device resets): {e}")
        time.sleep(6)
        dev = usb.core.find(idVendor=vid, idProduct=0x1465, backend=backend)
        if dev is not None:
            print("SWITCH OK: dongle is now 12d1:1465 (modem mode)")
            return
        dev = usb.core.find(idVendor=vid, idProduct=pid, backend=backend)
        if dev is None:
            print("SWITCH OK: 1520 device disappeared (likely switching)")
            return

    print("SWITCH FAILED: dongle still in storage mode")


if __name__ == "__main__":
    main()

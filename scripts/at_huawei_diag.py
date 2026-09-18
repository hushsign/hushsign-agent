#!/usr/bin/env python3
"""P1-S14 Huawei-specific SIM diagnostics (^SYSINFO etc.), tries all tty ports."""
import sys
import time

import serial

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"


def run(ser, cmd, wait=0.8, tail=b""):
    out = bytearray()
    ser.reset_input_buffer()
    ser.write(cmd)
    deadline = time.time() + (4.0 if tail else wait)
    while time.time() < deadline:
        n = ser.in_waiting
        if n:
            chunk = ser.read(n)
            out.extend(chunk)
            if tail and tail in out:
                break
            deadline = time.time() + (4.0 if tail else wait)
        time.sleep(0.05)
    return out


def main():
    for port in [PORT, PORT.replace("USB0", "USB1"), PORT.replace("USB0", "USB2")]:
        try:
            ser = serial.Serial(port, 115200, timeout=0.2)
        except Exception as e:
            print(f"--- {port}: open failed: {e}")
            continue
        print(f"=== {port}")
        print("ATI:", run(ser, b"ATI\r"))
        print("CPIN?:", run(ser, b"AT+CPIN?\r"))
        print("^SYSINFO:", run(ser, b"AT^SYSINFO\r"))
        print("^SYSINFOEX:", run(ser, b"AT^SYSINFOEX\r"))
        print("^CARDMODE:", run(ser, b"AT^CARDMODE\r"))
        print("^SIMST:", run(ser, b"AT^SIMST\r"))
        print("^RESET:", run(ser, b"AT^RESET\r", wait=1.5))
        ser.close()
        time.sleep(4)  # ^RESET re-initializes the stick


if __name__ == "__main__":
    main()

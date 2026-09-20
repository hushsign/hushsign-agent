#!/usr/bin/env python3
"""P1-S14 helper: send one NORMAL SMS (7-bit, SMS-SUBMIT) through a raw AT
port. Proves the modem+SIM+network SMS path independently of the technique.

Usage: python send_sms.py <port> <digits> [text]
"""
import sys
import time

import serial

GSM_ALPHABET = (
    "@\u00a3$\u00a5\u00e8\u00e9\u00f9\u00ec\u00f2\u00c7\n\u00d8\u00f8\r\u00c5\u00e5"
    "\u0394_\u03a6\u0393\u039b\u03a9\u03a0\u03a8\u03a3\u0398\u039e\u00a0\u00c6\u00e6"
    "\u00df\u00c9 !\"#\u00a4%&'()*+,-./0123456789:;<=>?\u00a1ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "\u00c4\u00d6\u00d1\u00dc\u00a7\u00bfabcdefghijklmnopqrstuvwxyz\u00e4\u00f6\u00f1\u00fc\u00e0")


def pack7(text: str) -> bytes:
    out = []
    carry = 0
    bits = 0
    for ch in text:
        code = GSM_ALPHABET.index(ch)
        carry |= code << bits
        bits += 7
        while bits >= 8:
            out.append(carry & 0xFF)
            carry >>= 8
            bits -= 8
    if bits:
        out.append(carry & 0xFF)
    return bytes(out)


def bcd_da(digits: str) -> bytes:
    digits = digits.lstrip("+")
    if len(digits) % 2:
        digits += "F"
    out = bytearray()
    for i in range(0, len(digits), 2):
        out.append(int(digits[i + 1] + digits[i], 16))
    return bytes(out)


def submit_pdu(digits: str, text: str) -> bytes:
    digits = digits.lstrip("+")
    da = bcd_da(digits)
    ud = pack7(text)
    return (bytes.fromhex("00")  # SMSC: use SIM default
            + bytes.fromhex("0100")  # FO=SMS-SUBMIT, no VPF | MR=0
            + bytes([len(digits)]) + bytes.fromhex("91") + da  # DA len = digit count
            + bytes.fromhex("0000")  # PID 0, DCS 0 (7-bit)
            + bytes([len(ud)]) + ud)


def run(ser, cmd, wait=0.6, tail=b"", long_wait=4.0):
    out = bytearray()
    ser.reset_input_buffer()
    ser.write(cmd)
    deadline = time.time() + (long_wait if tail else wait)
    while time.time() < deadline:
        n = ser.in_waiting
        if n:
            chunk = ser.read(n)
            out.extend(chunk)
            if tail and tail in out:
                break
            deadline = time.time() + (long_wait if tail else wait)
        time.sleep(0.05)
    return out


def text_mode_send(ser, digits, text):
    print("TEXT CSCS:", run(ser, b"AT+CSCS=\"GSM\"\r"))
    print("TEXT CMGF=1:", run(ser, b"AT+CMGF=1\r"))
    prompt = run(ser, b"AT+CMGS=\"%s\"\r" % digits.encode(), tail=b"> ", long_wait=5.0)
    print("TEXT prompt:", prompt)
    ser.reset_input_buffer()
    ser.write(text.encode("ascii"))
    time.sleep(0.05)
    ser.write(b"\x1a")
    return run(ser, b"", long_wait=20.0)


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "COM7"
    digits = sys.argv[2] if len(sys.argv) > 2 else "40756270541"
    text = sys.argv[3] if len(sys.argv) > 3 else "HushSign P1-S14 test"

    ser = serial.Serial(port, 115200, timeout=0.2)
    print("AT:", run(ser, b"AT\r"))
    print("CPIN?:", run(ser, b"AT+CPIN?\r"))
    print("CPMS:", run(ser, b"AT+CPMS=\"SM\",\"SM\",\"SM\"\r"))
    print("CMGF=0:", run(ser, b"AT+CMGF=0\r"))

    pdu = submit_pdu(digits, text)
    print(f"normal SMS PDU ({len(pdu)} octets): {pdu.hex().upper()}")
    prompt = run(ser, b"AT+CMGS=%d\r" % len(pdu), tail=b"> ", long_wait=5.0)
    print("prompt:", prompt)
    ser.reset_input_buffer()
    ser.write(pdu)
    time.sleep(0.05)
    ser.write(b"\x1a")
    print("PDU verdict:", run(ser, b"", long_wait=20.0))

    print("TEXT verdict:", text_mode_send(ser, digits, text))
    ser.close()


if __name__ == "__main__":
    main()

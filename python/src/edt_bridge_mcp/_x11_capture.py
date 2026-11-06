"""Write the private Xvfb display to PNG in a bounded subprocess."""

import ctypes as c
import struct
import sys
import zlib
from pathlib import Path


class _XImage(c.Structure):
    _fields_ = [
        ("width", c.c_int), ("height", c.c_int), ("xoffset", c.c_int), ("format", c.c_int),
        ("data", c.c_void_p), ("byte_order", c.c_int), ("bitmap_unit", c.c_int),
        ("bitmap_bit_order", c.c_int), ("bitmap_pad", c.c_int), ("depth", c.c_int),
        ("bytes_per_line", c.c_int), ("bits_per_pixel", c.c_int), ("red_mask", c.c_ulong),
        ("green_mask", c.c_ulong), ("blue_mask", c.c_ulong),
    ]


def _chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data)))


def capture(path):
    x11 = c.CDLL("libX11.so.6")
    x11.XOpenDisplay.argtypes = [c.c_char_p]
    x11.XOpenDisplay.restype = c.c_void_p
    x11.XDefaultRootWindow.argtypes = [c.c_void_p]
    x11.XDefaultRootWindow.restype = c.c_ulong
    x11.XGetImage.argtypes = [
        c.c_void_p, c.c_ulong, c.c_int, c.c_int, c.c_uint, c.c_uint, c.c_ulong, c.c_int,
    ]
    x11.XGetImage.restype = c.POINTER(_XImage)
    x11.XDestroyImage.argtypes = [c.POINTER(_XImage)]
    x11.XCloseDisplay.argtypes = [c.c_void_p]
    display = x11.XOpenDisplay(None)
    if not display:
        raise OSError("Could not open the private Xvfb display.")
    try:
        root = x11.XDefaultRootWindow(display)
        width, height = 1280, 900
        pointer = x11.XGetImage(display, root, 0, 0, width, height, c.c_ulong(-1).value, 2)
        if not pointer:
            raise OSError("Could not read the Xvfb root window.")
        try:
            image = pointer.contents
            masks = (image.red_mask, image.green_mask, image.blue_mask)
            if image.bits_per_pixel != 32 or image.byte_order != 0 or masks != (0xFF0000, 0xFF00, 0xFF):
                raise OSError("The Xvfb pixel format is unsupported.")
            stride = image.bytes_per_line
            pixels = c.string_at(image.data, stride * height)
        finally:
            x11.XDestroyImage(pointer)
    finally:
        x11.XCloseDisplay(display)

    scanline = width * 3 + 1
    rgb = bytearray(scanline * height)
    for y in range(height):
        src = pixels[y * stride:y * stride + width * 4]
        start = y * scanline + 1
        end = start + width * 3
        rgb[start:end:3] = src[2::4]
        rgb[start + 1:end:3] = src[1::4]
        rgb[start + 2:end:3] = src[0::4]
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header)
                     + _chunk(b"IDAT", zlib.compress(rgb)) + _chunk(b"IEND", b""))


if __name__ == "__main__":
    try:
        capture(Path(sys.argv[1]))
    except OSError as error:
        sys.exit(str(error))

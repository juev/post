#!/usr/bin/env python3
"""Writes the media fixtures of the attachment tests into app/src/test/resources/media.

Images come from ImageMagick (`magick`); the EXIF block of the JPEG and the MP3 frames are written
here byte by byte, so nothing else is needed. clip.mp4 is not made here: it was encoded on the
emulator with MediaCodec (H.264, 160x96, 30 fps, 45 frames, rotation 90) and is kept as it is.
"""

import pathlib
import struct
import subprocess
import sys

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/test/resources/media"


def magick(*args: str) -> None:
    subprocess.run(["magick", *args], check=True)


def exif_block() -> bytes:
    """An APP1 Exif segment: orientation 6 (rotate 90) and GPS 55.75 N, 37.6 E, big-endian."""
    def entry(tag: int, kind: int, count: int, value: bytes) -> bytes:
        return struct.pack(">HHI", tag, kind, count) + value.ljust(4, b"\0")

    gps_offset = 8 + 2 + 2 * 12 + 4
    ifd0 = struct.pack(">H", 2) + entry(0x0112, 3, 1, struct.pack(">H", 6)) + entry(0x8825, 4, 1, struct.pack(">I", gps_offset)) + struct.pack(">I", 0)
    data_offset = gps_offset + 2 + 5 * 12 + 4
    lat = struct.pack(">IIIIII", 55, 1, 45, 1, 0, 1)
    lon = struct.pack(">IIIIII", 37, 1, 36, 1, 0, 1)
    gps = (
        struct.pack(">H", 5)
        + entry(0x0000, 1, 4, bytes([2, 2, 0, 0]))
        + entry(0x0001, 2, 2, b"N\0")
        + entry(0x0002, 5, 3, struct.pack(">I", data_offset))
        + entry(0x0003, 2, 2, b"E\0")
        + entry(0x0004, 5, 3, struct.pack(">I", data_offset + len(lat)))
        + struct.pack(">I", 0)
    )
    tiff = b"MM" + struct.pack(">HI", 42, 8) + ifd0 + gps + lat + lon
    payload = b"Exif\0\0" + tiff
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def jpeg_with_exif() -> None:
    plain = OUT / "photo_plain.tmp.jpg"
    magick("-size", "64x48", "gradient:red-blue", "-strip", "-quality", "85", str(plain))
    data = plain.read_bytes()
    plain.unlink()
    assert data[:2] == b"\xff\xd8"
    (OUT / "photo_exif_rot90.jpg").write_bytes(data[:2] + exif_block() + data[2:])


def mp3() -> None:
    """48 silent MPEG-1 Layer III frames, 128 kbit/s, 44.1 kHz, mono: 48 * 1152 samples, about 1.25 s."""
    header = bytes([0xFF, 0xFB, 0x90, 0xC4])
    frame = header + bytes(417 - len(header))
    (OUT / "tone.mp3").write_bytes(frame * 48)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    jpeg_with_exif()
    magick("-size", "40x30", "xc:rgba(255,0,0,0.5)", "PNG32:" + str(OUT / "alpha.png"))
    magick("-size", "40x30", "xc:rgb(0,128,255)", "-alpha", "on", "PNG32:" + str(OUT / "opaque_rgba.png"))
    magick("-delay", "10", "-size", "32x24", "xc:red", "xc:blue", "xc:green", "-loop", "0", str(OUT / "animated.gif"))
    magick("-size", "32x24", "xc:green", str(OUT / "still.gif"))
    mp3()
    return 0


if __name__ == "__main__":
    sys.exit(main())

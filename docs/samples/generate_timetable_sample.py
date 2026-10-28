"""Generate the synthetic timetable screenshot used for OCR manual testing.

The image is drawn from the same OCR line fixture used by
ScheduleOcrParserTest, so the pixel layout and the parser test sample agree.
This is a synthetic sample, not a real university screenshot.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "app/src/test/resources/ocr/timetable_sample_1.tsv"
OUTPUT = Path(__file__).resolve().parent / "timetable_sample_1.png"
FONT_PATH = Path("C:/Windows/Fonts/msyh.ttc")

WIDTH, HEIGHT = 1440, 2400
BACKGROUND = (250, 251, 252)
GRID = (214, 222, 232)
HEADER_BG = (237, 242, 248)
INK = (30, 41, 59)
MUTED = (100, 116, 139)

DAY_LEFT = [0.14, 0.26, 0.38, 0.50, 0.62, 0.74, 0.86]
DAY_RIGHT = [0.26, 0.38, 0.50, 0.62, 0.74, 0.86, 0.98]
ROW_TOP = [0.175, 0.265, 0.355, 0.445, 0.535, 0.625, 0.715, 0.805]
ROW_BOTTOM = [0.265, 0.355, 0.445, 0.535, 0.625, 0.715, 0.805, 0.895]

# (day index, first row, last row, fill colour)
CELLS = [
    (0, 0, 1, (220, 235, 255)),
    (0, 3, 3, (227, 245, 233)),
    (1, 2, 3, (255, 233, 214)),
    (2, 5, 5, (240, 228, 255)),
    (3, 6, 6, (221, 245, 242)),
    (4, 4, 4, (255, 228, 239)),
    (5, 7, 7, (230, 233, 255)),
]


def load_lines():
    rows = []
    for raw in FIXTURE.read_text(encoding="utf-8").splitlines():
        if not raw.strip():
            continue
        text, left, top, right, bottom = raw.split("|")
        rows.append(
            (
                text,
                float(left) * WIDTH,
                float(top) * HEIGHT,
                float(right) * WIDTH,
                float(bottom) * HEIGHT,
            )
        )
    return rows


def font(size: int):
    return ImageFont.truetype(str(FONT_PATH), max(8, int(size)), index=0)


def text_units(text: str) -> float:
    units = 0.0
    for char in text:
        units += 1.0 if ord(char) > 127 else 0.55
    return max(units, 1.0)


def fitted_font(draw, text: str, width: float, height: float, cap: float):
    size = min(cap, height * 0.82, width / text_units(text))
    while size > 8:
        candidate = font(size)
        left, top, right, bottom = draw.textbbox((0, 0), text, font=candidate)
        if right - left <= width - 8 and bottom - top <= height:
            return candidate
        size -= 1
    return font(8)


def main():
    lines = load_lines()
    image = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw = ImageDraw.Draw(image)

    # Header band and title.
    draw.rectangle(
        [0, int(0.08 * HEIGHT), WIDTH, int(0.145 * HEIGHT)],
        fill=HEADER_BG,
    )
    title_lines = [row for row in lines if row[3] - row[1] > 300 and row[2] < 0.08 * HEIGHT]
    for text, left, top, right, bottom in title_lines:
        title_font = fitted_font(draw, text, right - left, bottom - top, 40)
        draw.text(
            ((left + right) / 2, (top + bottom) / 2),
            text,
            font=title_font,
            fill=INK,
            anchor="mm",
        )

    # Grid.
    for x in [0.0] + DAY_RIGHT:
        draw.line([(x * WIDTH, 0.145 * HEIGHT), (x * WIDTH, 0.91 * HEIGHT)], fill=GRID, width=2)
    for x in DAY_LEFT:
        draw.line([(x * WIDTH, 0.175 * HEIGHT), (x * WIDTH, 0.91 * HEIGHT)], fill=GRID, width=2)
    for y in [0.145, 0.175] + ROW_BOTTOM:
        draw.line([(0, y * HEIGHT), (WIDTH, y * HEIGHT)], fill=GRID, width=2)

    # Coloured course cells.
    for day, first_row, last_row, fill in CELLS:
        left = DAY_LEFT[day] * WIDTH + 3
        right = DAY_RIGHT[day] * WIDTH - 3
        top = ROW_TOP[first_row] * HEIGHT + 3
        bottom = ROW_BOTTOM[last_row] * HEIGHT - 3
        outline = tuple(max(0, channel - 24) for channel in fill)
        draw.rounded_rectangle(
            [left, top, right, bottom],
            radius=12,
            fill=fill,
            outline=outline,
            width=2,
        )

    # Header and period column text.
    for text, left, top, right, bottom in lines:
        center_y = (top + bottom) / 2
        if center_y < 0.08 * HEIGHT:
            continue
        if center_y < 0.145 * HEIGHT:
            header_font = fitted_font(draw, text, right - left, bottom - top, 30)
            draw.text(((left + right) / 2, center_y), text, font=header_font, fill=INK, anchor="mm")
            continue
        if left < DAY_LEFT[0] * WIDTH - 2:
            is_number = text.strip().isdigit()
            period_font = fitted_font(
                draw,
                text,
                right - left,
                bottom - top,
                30 if is_number else 22,
            )
            draw.text(
                ((left + right) / 2, center_y),
                text,
                font=period_font,
                fill=INK if is_number else MUTED,
                anchor="mm",
            )
            continue
        course_font = fitted_font(draw, text, right - left, bottom - top, 29)
        draw.text((left + 6, center_y), text, font=course_font, fill=INK, anchor="lm")

    draw.text(
        (WIDTH / 2, 0.945 * HEIGHT),
        "合成测试样例 · 非真实教务系统截图 · 用于 StudyOS 课表识别测试",
        font=font(26),
        fill=MUTED,
        anchor="mm",
    )
    image.save(OUTPUT, format="PNG", optimize=True)
    print(f"wrote {OUTPUT} ({WIDTH}x{HEIGHT})")


if __name__ == "__main__":
    main()

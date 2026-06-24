#!/usr/bin/env python3
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import pdfplumber


PRICE_RE = re.compile(r"\d[\d\s]*[.,]\s?\d{2}$")
DOSAGE_RE = re.compile(r"^\d+[.,]?\d*(?:\s*[–-]\s*\d+[.,]?\d*)?(?:\s*/\s*\d+[.,]?\d*)?$")
CROP_WORDS = {
    "яровая", "озимая", "озимый", "пшеница", "пшениц", "горох", "соя", "гречиха", "рожь", "тритикале",
    "ячмень", "ячмен", "овес", "рапс", "кукуруза", "шпеница", "coa",
}
REPRO_WORDS = {"tip-1", "tip-2", "tip-3", "тип-1", "тип-2", "тип-3", "пр-1", "пр-2", "пр-3", "pc-1", "рс-1", "рс-2", "элита", "эс"}


def clean(value):
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value).replace("\xa0", " ")).strip()


def normalize(value):
    return clean(value).lower().replace("ё", "е").replace("!", "1")


def canonical_section(tokens):
    mapping = {
        "соя": "СОЯ",
        "coa": "СОЯ",
        "горох": "ГОРОХ",
        "гречиха": "ГРЕЧИХА",
        "пшеница": "ПШЕНИЦА",
        "пшениц": "ПШЕНИЦА",
        "шпеница": "ПШЕНИЦА",
        "яровая": "ЯРОВАЯ",
        "озимая": "ОЗИМАЯ",
        "озимый": "ОЗИМАЯ",
    }
    return " ".join(mapping.get(normalize(token), token.upper()) for token in tokens)


def is_header_row(cells):
    joined = " ".join(cells).lower()
    return "препарат" in joined and ("цена" in joined or "упаковка" in joined)


def is_section_row(cells):
    non_empty = [cell for cell in cells if cell]
    if len(non_empty) != 1:
        return False
    value = non_empty[0]
    if len(value) > 120:
        return False
    return not looks_like_price(value)


def looks_like_price(value):
    text = clean(value)
    return bool(PRICE_RE.search(text)) or bool(re.search(r"\d[\d\s]*[.,]\s?\d{2}", text))


def looks_like_dosage(value):
    text = clean(value)
    if not text:
        return False
    if "га" in text.lower() or "кг" in text.lower() or "л" in text.lower():
        return True
    return bool(DOSAGE_RE.match(text.replace(" ", "")))


def pick_price(cells):
    for cell in reversed(cells):
        if looks_like_price(cell):
            match = re.search(r"\d[\d\s]*[.,]\s?\d{2}", cell)
            if match:
                return clean(match.group(0))
            return clean(cell)
    return ""


def pick_dosage(cells):
    for cell in reversed(cells):
        if looks_like_dosage(cell) and not looks_like_price(cell):
            return clean(cell)
    for cell in cells:
        if looks_like_dosage(cell) and not looks_like_price(cell):
            return clean(cell)
    return ""


def pick_packaging(cells, price, dosage):
    candidates = []
    for cell in cells:
        value = clean(cell)
        if not value or value == price or value == dosage:
            continue
        if re.search(r"\d", value) and ("/" in value or "х" in value.lower() or "x" in value.lower() or "л" in value.lower() or "кг" in value.lower()):
            candidates.append(value)
    return candidates[0] if candidates else ""


def row_to_record(file_name, row_number, section, cells):
    values = [clean(cell) for cell in cells]
    non_empty = [cell for cell in values if cell]
    if len(non_empty) < 3:
        return None

    name = non_empty[0]
    price = ""
    dosage = ""
    packaging = ""
    composition = ""

    if len(values) >= 6 and looks_like_price(values[-1]) and looks_like_price(values[-2]):
        composition = clean(values[1])
        packaging = clean(values[2])
        dosage = clean(values[3])
        price = clean(values[-1])
    elif len(values) >= 5 and looks_like_price(values[-1]) and looks_like_dosage(values[-2]):
        composition = clean(values[1])
        packaging = clean(values[-3])
        dosage = clean(values[-2])
        price = clean(values[-1])
    elif len(values) >= 4 and looks_like_price(values[-1]) and looks_like_dosage(values[-2]):
        packaging = clean(values[-3])
        dosage = clean(values[-2])
        price = clean(values[-1])
        composition = clean(values[1]) if len(values) > 4 else ""
    else:
        price = pick_price(non_empty)
        dosage = pick_dosage(non_empty)
        packaging = pick_packaging(non_empty[1:], price, dosage)
        composition_parts = []
        for cell in non_empty[1:]:
            if cell in {price, dosage, packaging}:
                continue
            composition_parts.append(cell)
        composition = " ".join(composition_parts).strip()

    columns = {
        "Позиция": name,
        "Сырой текст": " | ".join(non_empty),
    }
    if section:
        columns["Раздел"] = section
    if composition:
        columns["Состав"] = composition
    if packaging:
        columns["Упаковка"] = packaging
    if dosage:
        columns["Норма расхода"] = dosage
    if price:
        columns["Цена"] = price
    if not price and not dosage and not packaging:
        return None

    return {
        "rowId": f"{file_name}#PDF#{row_number}",
        "sourceFile": file_name,
        "sheetName": "PDF",
        "rowNumber": row_number,
        "columns": columns,
        "nameGuess": name,
        "section": section or "",
    }


def extract_tables(path):
    results = []
    row_number = 0
    any_words = False
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            words = page.extract_words() or []
            any_words = any_words or bool(words)
            section = ""
            for table in page.extract_tables() or []:
                for raw_row in table:
                    cells = [clean(cell) for cell in raw_row]
                    if not any(cells):
                        continue
                    if is_header_row(cells):
                        continue
                    if is_section_row(cells):
                        section = cells[0]
                        continue
                    record = row_to_record(Path(path).name, row_number + 1, section, cells)
                    if record:
                        row_number += 1
                        record["rowNumber"] = row_number
                        record["rowId"] = f"{Path(path).name}#PDF#{row_number}"
                        results.append(record)
    return results, any_words


def is_noise_line(line):
    text = normalize(line)
    if not text or len(text) < 4:
        return True
    return (
        "прайс" in text
        or "страница" in text
        or "телефон" in text
        or "сайт" in text
        or "эл. почт" in text
        or text.startswith("для получения подробной информации")
    )


def extract_ocr_content_lines(text):
    lines = [clean(raw_line) for raw_line in text.splitlines()]
    started = False
    content = []
    for line in lines:
        if not started and "руб./т" in normalize(line):
            started = True
            continue
        if not started:
            continue
        if not line or is_noise_line(line):
            continue
        content.append(line)
    return content


def is_product_start_line(line):
    normalized = normalize(line)
    repro_match = re.search(r"\b(?:tip|тип|пр|pc|рс)-?\d\b|\bэлита\b", normalized, re.IGNORECASE)
    if repro_match:
        before = clean(line[:repro_match.start()].replace("|", " "))
        tokens = before.split()
        while tokens and normalize(tokens[0]) in CROP_WORDS:
            tokens.pop(0)
        return bool(tokens)
    return "|" in line and bool(re.match(r"^[А-ЯA-ZЁ][А-ЯA-ZЁa-zа-яё-]+", line)) and bool(re.search(r"\d[\d\s-]{4,}\s*$", line))


def parse_ocr_text(file_name, text):
    results = []
    content_lines = extract_ocr_content_lines(text)
    blocks = []
    current = ""
    for line in content_lines:
        if is_product_start_line(line):
            if current:
                blocks.append(current)
            current = line
            continue
        if current:
            current = f"{current} {line}".strip()
    if current:
        blocks.append(current)

    row_number = 0
    current_section = ""
    for block in blocks:
        parsed = parse_ocr_block(block, current_section)
        if parsed is None:
            continue
        name, section, description, volume, price = parsed
        current_section = section or current_section
        row_number += 1
        columns = {
            "Позиция": name,
            "Сырой текст": block,
            "Цена": price,
        }
        if current_section:
            columns["Раздел"] = current_section
        if volume:
            columns["Объем"] = volume
        if description:
            columns["Состав"] = description
        results.append({
            "rowId": f"{file_name}#PDF#{row_number}",
            "sourceFile": file_name,
            "sheetName": "PDF",
            "rowNumber": row_number,
            "columns": columns,
            "nameGuess": name,
            "section": current_section or "",
        })
    return results


def parse_ocr_block(block, current_section):
    parts = [clean(part) for part in block.split("|") if clean(part)]
    if not parts:
        return None

    first_part_tokens = parts[0].split()
    section_tokens = []
    while first_part_tokens and normalize(first_part_tokens[0]) in CROP_WORDS:
        section_tokens.append(first_part_tokens.pop(0))
    section = canonical_section(section_tokens) if section_tokens else current_section

    name_tokens = []
    repro_seen = False
    for token in first_part_tokens:
        normalized = normalize(token)
        name_tokens.append(token)
        if normalized in REPRO_WORDS or re.match(r"^(tip|тип|пр|pc|рс)-?\d$", normalized):
            repro_seen = True
            break
    if not name_tokens:
        return None

    description_parts = []
    if repro_seen:
        description_parts.extend(first_part_tokens[len(name_tokens):])
    else:
        if len(name_tokens) > 1:
            description_parts.extend(name_tokens[1:])
            name_tokens = name_tokens[:1]

    for part in parts[1:]:
        normalized_part = normalize(part)
        if not repro_seen:
            repro_prefix = re.match(r"^(элита|(?:tip|тип|пр|pc|рс)-?\d)\b", normalized_part, re.IGNORECASE)
            if repro_prefix:
                original_prefix = clean(part[:repro_prefix.end()])
                name_tokens.append(original_prefix)
                part = clean(part[repro_prefix.end():])
                repro_seen = True
        if part:
            description_parts.append(part)

    body = clean(" ".join(description_parts))
    qty_price_match = re.search(r"(По заявке|По|\d+[.,]?\d*)\s+(\d[\d\s-]{3,})", body, re.IGNORECASE)
    if not qty_price_match:
        return None

    name = clean(" ".join(name_tokens))
    if len(name) < 3:
        return None
    volume = clean(qty_price_match.group(1))
    if normalize(volume) == "по":
        volume = "По заявке"
    price = clean(qty_price_match.group(2))
    description = clean(body[:qty_price_match.start()] + " " + body[qty_price_match.end():])
    return name, section, description, volume, price


def extract_via_ocr(path):
    file_name = Path(path).name
    with tempfile.TemporaryDirectory(prefix="alga_pdf_ocr_") as tmpdir:
        prefix = os.path.join(tmpdir, "page")
        subprocess.run(["pdftoppm", "-r", "300", "-png", path, prefix], check=True, capture_output=True)
        texts = []
        for image_path in sorted(Path(tmpdir).glob("page-*.png")):
            proc = subprocess.run(
                ["tesseract", str(image_path), "stdout", "-l", "rus+eng", "--psm", "4"],
                check=True,
                capture_output=True,
                text=True,
            )
            texts.append(proc.stdout)
        return parse_ocr_text(file_name, "\n".join(texts))


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "pdf path required"}, ensure_ascii=False))
        sys.exit(1)

    path = sys.argv[1]
    rows, any_words = extract_tables(path)
    if rows:
        print(json.dumps(rows, ensure_ascii=False))
        return

    if not any_words:
        rows = extract_via_ocr(path)
        print(json.dumps(rows, ensure_ascii=False))
        return

    print(json.dumps([], ensure_ascii=False))


if __name__ == "__main__":
    main()

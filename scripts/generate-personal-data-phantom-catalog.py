#!/usr/bin/env python3
"""从本地审核过的官方公开脚本静态提取幻境武器目录；不联网、不执行 JavaScript。

用法：python3 scripts/generate-personal-data-phantom-catalog.py --source-file <本地公开分块>
      --captured-on YYYY-MM-DD
来源摘要或目录结构变化时必须重新审核，不能绕过检查执行下载的脚本。
"""

import argparse
import datetime
import hashlib
import json
import re
from decimal import Decimal
from pathlib import Path


SOURCE_NAME = 'chunk-7690576a.97d2f158.js'
SOURCE_SHA256 = '60f51032cf4dac74e7c430a4bcbbe20e277f14cc329d27d48f770f35af3a9f7d'
STAGES = [('a', 'penumbrae', 47869), ('r', 'umbrae', 47006), ('o', 'obscurum', 50032),
          ('l', 'eclipticum', 50978), ('c', 'occultum', 51000)]


class LiteralReader:
    """只解析对象、数组、JSON 字符串与精确整数；接受 51e3，拒绝表达式。"""

    def __init__(self, source, offset=0):
        self.source = source
        self.offset = offset

    def whitespace(self):
        while self.offset < len(self.source) and self.source[self.offset].isspace():
            self.offset += 1

    def consume(self, token):
        self.whitespace()
        if not self.source.startswith(token, self.offset):
            raise ValueError('静态字面量语法与已审核格式不同')
        self.offset += len(token)

    def value(self):
        self.whitespace()
        if self.offset >= len(self.source):
            raise ValueError('静态字面量未结束')
        char = self.source[self.offset]
        if char == '"':
            value, length = json.JSONDecoder().raw_decode(self.source[self.offset:])
            self.offset += length
            return value
        if char == '[':
            self.consume('[')
            rows = []
            self.whitespace()
            while not self.source.startswith(']', self.offset):
                if rows:
                    self.consume(',')
                rows.append(self.value())
                self.whitespace()
            self.consume(']')
            return rows
        if char == '{':
            self.consume('{')
            fields = {}
            self.whitespace()
            while not self.source.startswith('}', self.offset):
                if fields:
                    self.consume(',')
                self.whitespace()
                if self.source.startswith('"', self.offset):
                    key = self.value()
                else:
                    match = re.match(r'[A-Za-z_$][A-Za-z0-9_$]*', self.source[self.offset:])
                    if match is None:
                        raise ValueError('目录字段名无效')
                    key = match.group()
                    self.offset += len(key)
                self.consume(':')
                if key in fields:
                    raise ValueError('目录字段重复')
                fields[key] = self.value()
                self.whitespace()
            self.consume('}')
            return fields
        match = re.match(r'-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?', self.source[self.offset:])
        if match is None:
            raise ValueError('仅允许静态目录字面量')
        token = match.group()
        self.offset += len(token)
        if self.offset < len(self.source) and self.source[self.offset] not in ',]} \t\r\n':
            raise ValueError('数字后不允许表达式或标识符')
        number = Decimal(token)
        if not number.is_finite() or number != number.to_integral_value() or not -2_147_483_648 <= number <= 2_147_483_647:
            raise ValueError('目录数字必须为 32 位精确整数')
        return int(number)


def item(row):
    if set(row) != {'ItemId', 'Name', 'Icon', 'ItemUICategory'}:
        raise ValueError('物品目录字段变化，需要重新审核')
    for key in ['ItemId', 'Icon', 'ItemUICategory']:
        if type(row[key]) is not int or row[key] <= 0:
            raise ValueError('物品目录编号无效')
    if not isinstance(row['Name'], str) or not row['Name'].strip():
        raise ValueError('物品名称缺失')
    return dict(itemId=row['ItemId'], name=row['Name'], iconId=row['Icon'], itemUiCategory=row['ItemUICategory'])


def extract_catalog(source):
    module = source.index('"2cff":function(e,t,i)')
    reader = LiteralReader(source, source.index('var n=', module))
    reader.consume('var n=')
    reader.value()  # 该导出为稀有物品，非武器目录。
    arrays = {}
    for variable in ['a', 'r', 'o', 's', 'l', 'c']:
        reader.consume(',' + variable + '=')
        arrays[variable] = reader.value()
    reader.consume('}')
    weapons = []
    for variable, stage, first in STAGES:
        rows = [item(row) for row in arrays[variable]]
        if [row['itemId'] for row in rows] != list(range(first, first + 22)):
            raise ValueError('五阶完整编号范围变化，需要重新审核')
        weapons.extend(dict(stage=stage, **row) for row in rows)
    materials = [item(row) for row in arrays['s']]
    soul = [row for row in materials if row['itemUiCategory'] == 63]
    if [row['itemId'] for row in soul] != list(range(47744, 47750)):
        raise ValueError('半魂晶目录变化，需要重新审核')
    page = source.index('"9da6":function(e,t,i)')
    demiatma = LiteralReader(source, source.index('H=[', page) + 2).value()
    if [row['id'] for row in demiatma] != [50974, 50975, 50976]:
        raise ValueError('消幻晶目录变化，需要重新审核')
    normalized_demi = []
    for row in demiatma:
        if set(row) != {'id', 'name', 'icon'} or type(row['icon']) is not int or row['icon'] <= 0 or not isinstance(row['name'], str) or not row['name'].strip():
            raise ValueError('消幻晶字段无效')
        normalized_demi.append(dict(itemId=row['id'], name=row['name'], iconId=row['icon']))
    return dict(weapons=weapons, soulCrystals=soul, demiatma=normalized_demi)


def document(source_bytes, captured_on):
    datetime.date.fromisoformat(captured_on)
    digest = hashlib.sha256(source_bytes).hexdigest()
    if digest != SOURCE_SHA256 or not source_bytes.startswith(b'(window["webpackJsonp"]'):
        raise ValueError('不是已审核的公开分块；源码更新或挑战 HTML 必须重新核对')
    return dict(formatVersion=1, provenance=dict(
        capturedOn=captured_on,
        source=dict(url='https://ff14risingstones.web.sdo.com/mob/static/js/' + SOURCE_NAME,
                    sha256=digest, byteCount=len(source_bytes)),
        modules=['2cff', '9da6'], stageCounts={stage: 22 for _, stage, _ in STAGES},
        excludedWeaponIds=[], excludedNonSoulCrystalMaterialIds=[47740, 47741, 47743],
        note='公开静态目录；五阶各二十二件，无武器排除。51e3 按十进制精确保存为51000；仅半魂晶取类别63。',
    ), **extract_catalog(source_bytes.decode('utf-8')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-file', type=Path, required=True)
    parser.add_argument('--captured-on', required=True)
    parser.add_argument('--output', type=Path, default=Path(
        'personal-data-data/src/main/resources/top/cxmeow/risingstones/feature/personaldata/data/official-phantom-weapons.json'))
    args = parser.parse_args()
    result = document(args.source_file.read_bytes(), args.captured_on)
    encoded = (json.dumps(result, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)
    print(json.dumps(dict(bytes=len(encoded), weapons=len(result['weapons']), soulCrystals=len(result['soulCrystals']),
                          demiatma=len(result['demiatma']), sha256=hashlib.sha256(encoded).hexdigest())))


if __name__ == '__main__':
    main()

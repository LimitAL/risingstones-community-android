#!/usr/bin/env python3
"""从本地已审核的官方公开脚本提取附加目录；不联网、不执行 JavaScript。

只识别指定模块中的数组/对象/字符串/整数等静态字面量。源码位置、字段或已审核排除规则变化
时必须重新审阅页面消费逻辑；禁止使用 eval 或运行下载的脚本。旧目录资源和旧生成器不受影响。
"""

import argparse
import datetime
import hashlib
import json
import re
from pathlib import Path


SOURCES = {
    "fishing": "chunk-2395dd56.6833c83b.js",
    "frontline": "chunk-5427d794.02834a98.js",
    "glamour": "chunk-e60b7080.4dd92828.js",
}


class LiteralReader:
    """拒绝函数、引用、运算及模板字符串，只解析所需静态数据。"""
    def __init__(self, text, offset):
        self.text = text
        self.offset = offset

    def whitespace(self):
        while self.offset < len(self.text) and self.text[self.offset].isspace():
            self.offset += 1

    def consume(self, token):
        self.whitespace()
        if not self.text.startswith(token, self.offset):
            raise ValueError("静态字面量语法与已审核格式不同")
        self.offset += len(token)

    def value(self):
        self.whitespace()
        char = self.text[self.offset]
        if char == '"':
            value, length = json.JSONDecoder().raw_decode(self.text[self.offset:])
            self.offset += length
            return value
        if char == '[':
            self.consume('[')
            values = []
            self.whitespace()
            while self.text[self.offset] != ']':
                if values:
                    self.consume(',')
                values.append(self.value())
                self.whitespace()
            self.consume(']')
            return values
        if char == '{':
            self.consume('{')
            values = {}
            self.whitespace()
            while self.text[self.offset] != '}':
                if values:
                    self.consume(',')
                self.whitespace()
                if self.text[self.offset] == '"':
                    key = self.value()
                else:
                    match = re.match(r'[A-Za-z_$][A-Za-z0-9_$]*', self.text[self.offset:])
                    if match is None:
                        raise ValueError("目录字段名无效")
                    key = match.group()
                    self.offset += len(key)
                self.consume(':')
                if key in values:
                    raise ValueError("目录对象含重复字段")
                values[key] = self.value()
                self.whitespace()
            self.consume('}')
            return values
        for token, value in [('null', None), ('true', True), ('false', False)]:
            if self.text.startswith(token, self.offset):
                self.offset += len(token)
                return value
        match = re.match(r'-?(?:0|[1-9][0-9]*)', self.text[self.offset:])
        if match is None:
            raise ValueError("仅允许静态目录字面量，不能执行表达式")
        self.offset += len(match.group())
        return int(match.group())


def extract(source, module, assignment):
    module_at = source.index(module)
    assignment_at = source.index(assignment, module_at)
    return LiteralReader(source, assignment_at + len(assignment)).value()


def integer(value, minimum=1):
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        raise ValueError("目录整数必须为整数或十进制数字字符串")
    if isinstance(value, str) and re.fullmatch(r'[0-9]+', value) is None:
        raise ValueError("目录整数格式无效")
    result = int(value)
    if not minimum <= result <= 2_147_483_647:
        raise ValueError("目录整数超出范围")
    return result


def text(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("目录文字缺失")
    return value


def unique(rows, field):
    values = [row[field] for row in rows]
    if len(values) != len(set(values)):
        raise ValueError("目录标识重复")
    return rows


def normalize(raw):
    ocean = unique([dict(itemId=integer(row['ItemId']), iconId=integer(row['Icon']), name=text(row['Name']))
                    for row in raw['oceanFish']], 'itemId')
    def achievements(rows, has_icons):
        return unique([dict(achievementId=integer(row['achieve_id']), name=text(row['achieve_name']),
                            detail=text(row['achieve_detail']),
                            iconId=integer(row['achieve_icon']) if has_icons else None)
                       for row in rows], 'achievementId')
    fishing = achievements(raw['fishingAchievements'], True)
    frontline = achievements(raw['frontlineAchievements'], False)
    unique(raw['vanityCategories'], 'id')
    categories = []
    for row in raw['vanityCategories']:
        major = integer(row['OrderMajor'], 0)
        if major not in (1, 3, 4):
            continue
        identifier = integer(row['id'])
        categories.append(dict(id=identifier, name=text(row['Name']), iconId=integer(row['Icon']),
                               majorOrder=major, minorOrder=integer(row['OrderMinor'], 0),
                               selectable=identifier not in (7, 9, 62)))
    if {row['id'] for row in categories if not row['selectable']} != {7, 9, 62}:
        raise ValueError("已审核的隐藏选择器类别变化")
    categories.sort(key=lambda row: (row['majorOrder'], row['minorOrder'], row['id']))
    return dict(oceanFish=ocean, fishingAchievements=fishing, frontlineAchievements=frontline,
                vanityCategories=categories)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-directory', type=Path, required=True, help='本地已审核公开脚本目录')
    parser.add_argument('--captured-on', required=True, help='官方资源核对日期 YYYY-MM-DD')
    parser.add_argument('--output', type=Path, default=Path(
        'personal-data-data/src/main/resources/top/cxmeow/risingstones/feature/personaldata/data/official-supplementary-catalogs.json'))
    args = parser.parse_args()
    datetime.date.fromisoformat(args.captured_on)
    source_text = {}
    provenance = []
    for key, filename in SOURCES.items():
        source_bytes = (args.source_directory / filename).read_bytes()
        if not source_bytes.startswith(b'(window["webpackJsonp"]'):
            raise ValueError('来源必须为官方公开脚本，不能使用挑战 HTML')
        source_text[key] = source_bytes.decode('utf-8')
        provenance.append(dict(url='https://ff14risingstones.web.sdo.com/mob/static/js/' + filename,
                               sha256=hashlib.sha256(source_bytes).hexdigest(), byteCount=len(source_bytes)))
    raw = dict(
        oceanFish=extract(source_text['fishing'], '"4fc3":function', ',y='),
        fishingAchievements=extract(source_text['fishing'], '"9bee":function', ',r='),
        frontlineAchievements=extract(source_text['frontline'], '"9bee":function', ',a='),
        vanityCategories=extract(source_text['glamour'], '7799:function', 'var a='),
    )
    content = normalize(raw)
    document = dict(formatVersion=1, provenance=dict(
        capturedOn=args.captured_on, sources=provenance,
        rawCounts={key: len(value) for key, value in raw.items()},
        includedVanityMajorOrders=[1, 3, 4], nonSelectableVanityIds=[7, 9, 62],
        excludedVanityIds=[integer(row['id'], 0) for row in raw['vanityCategories']
                          if integer(row['OrderMajor'], 0) not in (1, 3, 4)],
        note='官方公开附加目录；投影隐藏选项仍保留用于全部记录关联，前线成就无编号图标。',
    ), **content)
    encoded = (json.dumps(document, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)
    print(json.dumps(dict(bytes=len(encoded), counts={key: len(value) for key, value in content.items()},
                          sha256=hashlib.sha256(encoded).hexdigest()), ensure_ascii=False))


if __name__ == '__main__':
    main()

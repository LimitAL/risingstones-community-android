#!/usr/bin/env python3
"""从已人工审核的本地官方目录字面量生成离线资源；不联网、不执行 JavaScript。

输入 JSON 保存官网字面量的原字段，按主页面消费顺序排列；根键为 fish、savageSeries、sets、accessories、
stains、excludedSets。--source-directory 保存对应公开 JS，仅用于计算来源摘要。
此脚本不能证明输入与源码等价；更新前必须审核公开页面字段和排除规则。
"""

import argparse
import datetime
import hashlib
import json
from pathlib import Path


SOURCE_FILES = (
    "chunk-2395dd56.6833c83b.js",
    "chunk-614eb724.8da5b1c5.js",
    "chunk-e60b7080.4dd92828.js",
)


def integer(value, minimum=0):
    if isinstance(value, bool):
        raise ValueError("目录整数不能是布尔值")
    if isinstance(value, str) and not value.isdecimal():
        raise ValueError("目录整数格式无效")
    result = int(value)
    if result != value and not isinstance(value, str):
        raise ValueError("目录整数不能丢失精度")
    if result < minimum:
        raise ValueError("目录整数超出范围")
    return result


def text(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError("目录名称缺失")
    return value


def unique(rows, key):
    ids = [row[key] for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("目录含重复标识")
    return rows


def normalize(raw):
    fish = unique([
        dict(itemId=integer(row["ItemId"], 1), iconId=integer(row["Icon"], 1),
             name=text(row["Name"]), patch=text(row["Patch"]))
        for row in raw["fish"]
    ], "itemId")
    series = []
    for row in raw["savageSeries"]:
        tiers = []
        for tier in row["data"]:
            achievement_only = tier.get("achievementOnly", False)
            if not isinstance(achievement_only, bool):
                raise ValueError("成就层级标记必须为布尔值")
            tiers.append(dict(
                nameEnglish=text(tier["subName"]["en"]), nameChinese=text(tier["subName"]["cn"]),
                achievementOnly=achievement_only, achievementText=tier.get("achievementText"),
                raids=[dict(instanceId=integer(raid["instanceId"], 1), name=text(raid["name"]),
                            imageId=integer(raid["image"], 1) if raid.get("image") is not None else None)
                       for raid in tier["raids"]],
            ))
        series.append(dict(name=text(row["name"]), abbreviation=text(row["abbr"]), tiers=tiers))
    unique([raid for row in series for tier in row["tiers"] for raid in tier["raids"]], "instanceId")
    excluded = {integer(value, 1) for value in raw["excludedSets"]}
    if excluded != {52594}:
        raise ValueError("官方排除列表变化，需先重新审核生成规则")
    sets = []
    for row in raw["sets"]:
        set_id = integer(row["MirageSetId"], 1)
        if set_id in excluded:
            continue
        if len(row["items"]) != 9:
            raise ValueError("套装位置数量变化，需重新审核")
        items = [dict(slotIndex=slot, itemId=integer(item["ItemId"], 1),
                      name=text(item["Name"]), iconId=integer(item["Icon"], 1))
                 for slot, item in enumerate(row["items"]) if integer(item["ItemId"]) != 0]
        sets.append(dict(mirageSetId=set_id, name=text(row["MirageSetName"]),
                         iconId=integer(row["MirageSetIcon"], 1), items=items))
    unique(sets, "mirageSetId")
    accessories = []
    for row in raw["accessories"]:
        if row["Singular"] is None and integer(row["Icon"]) == 0:
            continue
        accessories.append(dict(id=integer(row["ID"], 1), iconId=integer(row["Icon"], 1),
                                name=text(row["Singular"])))
    unique(accessories, "id")
    stains = []
    for row in raw["stains"]:
        if row["Name"] is None:
            if integer(row["StainId"]) not in (126, 127, 128) or integer(row["Color"]) != 0:
                raise ValueError("染剂占位项变化，需重新审核")
            continue
        if not isinstance(row["IsMetallic"], bool):
            raise ValueError("金属染剂标记必须为布尔值")
        color = integer(row["Color"])
        if color > 0xFFFFFF:
            raise ValueError("染剂颜色不是 RGB 值")
        stains.append(dict(stainId=integer(row["StainId"]), name=text(row["Name"]),
                           color=color, isMetallic=row["IsMetallic"]))
    unique(stains, "stainId")
    return dict(fish=fish, savageSeries=series, sets=sets, fashionAccessories=accessories, stains=stains)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="已审核的原字段字面量 JSON")
    parser.add_argument("--source-directory", type=Path, required=True, help="已审核的公开 JS 所在目录")
    parser.add_argument("--captured-on", required=True, help="公开资源核对日期 YYYY-MM-DD")
    parser.add_argument("--output", type=Path, default=Path(
        "personal-data-data/src/main/resources/top/cxmeow/risingstones/feature/personaldata/data/official-catalogs.json"))
    args = parser.parse_args()
    datetime.date.fromisoformat(args.captured_on)
    input_bytes = args.input.read_bytes()
    raw = json.loads(input_bytes)
    content = normalize(raw)
    source_info = []
    for filename in SOURCE_FILES:
        source_bytes = (args.source_directory / filename).read_bytes()
        if not source_bytes.startswith(b'(window["webpackJsonp"]'):
            raise ValueError("来源必须是审核过的官方公开 JS，不能使用挑战 HTML")
        source_info.append(dict(
            url="https://ff14risingstones.web.sdo.com/mob/static/js/" + filename,
            sha256=hashlib.sha256(source_bytes).hexdigest(), byteCount=len(source_bytes),
        ))
    result = dict(formatVersion=1, provenance=dict(
        capturedOn=args.captured_on, sources=source_info,
        reviewedInputSha256=hashlib.sha256(input_bytes).hexdigest(),
        rawCounts={key: len(raw[key]) for key in ("fish", "savageSeries", "sets", "accessories", "stains")},
        excludedSetIds=sorted(raw["excludedSets"]),
        excludedAccessoryIds=sorted(integer(row["ID"]) for row in raw["accessories"]
                                   if row["Singular"] is None and integer(row["Icon"]) == 0),
        excludedStainIds=sorted(integer(row["StainId"]) for row in raw["stains"] if row["Name"] is None),
        note="官方公开目录的审核快照；套装空位移除但保留原位置，染剂0无染色保留。与宿主schema-v1文档无关。",
    ), **content)
    encoded = (json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)
    print(json.dumps(dict(bytes=len(encoded), counts={key: len(value) for key, value in content.items()},
                          sha256=hashlib.sha256(encoded).hexdigest()), ensure_ascii=False))


if __name__ == "__main__":
    main()

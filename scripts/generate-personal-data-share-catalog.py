#!/usr/bin/env python3
"""从已审核的官方公开分块静态生成分享目录；不联网、不执行 JavaScript。

用法：python3 scripts/generate-personal-data-share-catalog.py --source-directory <本地公开分块目录>
      --captured-on YYYY-MM-DD

输入摘要必须与本文件固定的 SHA-256 一致。官网目录、模块位置或固定绝境映射发生变化时，
必须重新审核后更新本脚本和资源，不能下载或执行网页脚本来绕过验证。
"""

import argparse
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path


SOURCES = {
    "achievements": (
        "chunk-f27e63c4.14a654ff.js",
        "260612e1b09ba45df5d6ed5b0e42dd115b615b3a09a646a3fd22e485204d5067",
    ),
    "phantom": (
        "chunk-7690576a.97d2f158.js",
        "60f51032cf4dac74e7c430a4bcbbe20e277f14cc329d27d48f770f35af3a9f7d",
    ),
}
ULTIMATES = ((733, 1993), (777, 2107), (887, 2444), (968, 3074), (1122, 3162),
             (1238, 3617), (1363, 4069))


def phantom_generator():
    script = Path(__file__).with_name("generate-personal-data-phantom-catalog.py")
    spec = importlib.util.spec_from_file_location("personal_data_phantom_catalog", script)
    if spec is None or spec.loader is None:
        raise ValueError("无法载入已审核的幻境静态字面量解析器")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verified_source(directory, key):
    filename, expected_sha256 = SOURCES[key]
    raw = (directory / filename).read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != expected_sha256 or not raw.startswith(b'(window["webpackJsonp"]'):
        raise ValueError("不是已审核的官方公开分块；来源更新或挑战 HTML 必须重新核对")
    return raw, dict(
        url="https://ff14risingstones.web.sdo.com/mob/static/js/" + filename,
        sha256=digest,
        byteCount=len(raw),
    )


def extract(achievement_source, phantom_source):
    parser = phantom_generator()
    achievement_module = achievement_source.index('"9bee":function')
    achievements = parser.LiteralReader(
        achievement_source,
        achievement_source.index('var n=', achievement_module) + len('var n='),
    ).value()
    by_id = {row['achieve_id']: row for row in achievements}
    if len(by_id) != len(achievements):
        raise ValueError("官方成就目录有重复编号")
    ultimate = []
    for territory_type, achievement_id in ULTIMATES:
        row = by_id.get(achievement_id)
        if row is None or not {
                'achieve_id', 'achieve_name', 'achieve_detail', 'medal_id', 'medal_type', 'medal_type_id',
        }.issubset(row):
            raise ValueError("绝境成就字段或固定映射变化，需要重新审核")
        if not isinstance(row['medal_id'], int) or row['medal_id'] <= 0:
            raise ValueError("绝境成就奖章编号无效")
        if not isinstance(row['achieve_name'], str) or not row['achieve_name'].strip() or \
                not isinstance(row['achieve_detail'], str) or not row['achieve_detail'].strip():
            raise ValueError("绝境成就文字缺失")
        ultimate.append(dict(territoryType=territory_type, achievementId=achievement_id,
                             medalId=row['medal_id'], name=row['achieve_name'],
                             detail=row['achieve_detail']))

    jobs = parser.LiteralReader(
        phantom_source,
        phantom_source.index('T=[{Id:0') + len('T='),
    ).value()
    if not isinstance(jobs, list) or [row.get('Id') for row in jobs] != list(range(24)):
        raise ValueError("辅助职业目录不再是完整的 0..23 固定顺序")
    normalized_jobs = []
    for row in jobs:
        if set(row) != {'Id', 'Name', 'NameEnglish', 'Description', 'LevelMax', 'JobIndex', 'Icon'} or \
                not isinstance(row['Name'], str) or not row['Name'].strip() or \
                not isinstance(row['Icon'], str) or not row['Icon'].isdecimal() or int(row['Icon']) <= 0 or \
                not isinstance(row['LevelMax'], int) or row['LevelMax'] < 0:
            raise ValueError("辅助职业字段变化，需要重新审核")
        normalized_jobs.append(dict(id=row['Id'], name=row['Name'], iconId=int(row['Icon']),
                                    levelCap=row['LevelMax']))

    weapons = parser.extract_catalog(phantom_source)['weapons'][:44]
    if len(weapons) != 44 or [row['itemId'] for row in weapons] != \
            list(range(47869, 47891)) + list(range(47006, 47028)):
        raise ValueError("前两阶幻境武器目录变化，需要重新审核")
    normalized_weapons = [dict(itemId=row['itemId'], itemUiCategoryId=row['itemUiCategory'])
                          for row in weapons]
    return dict(ultimateAchievements=ultimate, phantomJobs=normalized_jobs, weapons=normalized_weapons)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-directory', type=Path, required=True, help='本地已审核官方公开分块目录')
    parser.add_argument('--captured-on', required=True, help='公开资源核对日期 YYYY-MM-DD')
    parser.add_argument('--output', type=Path, default=Path(
        'personal-data-data/src/main/resources/top/cxmeow/risingstones/feature/personaldata/data/official-share-catalog.json'))
    args = parser.parse_args()
    datetime.date.fromisoformat(args.captured_on)
    achievement_bytes, achievement_provenance = verified_source(args.source_directory, 'achievements')
    phantom_bytes, phantom_provenance = verified_source(args.source_directory, 'phantom')
    content = extract(achievement_bytes.decode('utf-8'), phantom_bytes.decode('utf-8'))
    document = dict(formatVersion=1, provenance=dict(
        capturedOn=args.captured_on,
        sources=[achievement_provenance, phantom_provenance],
        ultimateTerritoryTypes=[territory for territory, _ in ULTIMATES],
        phantomJobIds=list(range(24)),
        weaponItemRanges=[[47869, 47890], [47006, 47027]],
        note='官方公开分享目录；绝境映射、辅助职业和前两阶幻境武器均由审核过的静态字面量提取。',
    ), **content)
    encoded = (json.dumps(document, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)
    print(json.dumps(dict(bytes=len(encoded), sha256=hashlib.sha256(encoded).hexdigest(),
                          counts={key: len(value) for key, value in content.items()}), ensure_ascii=False))


if __name__ == '__main__':
    main()

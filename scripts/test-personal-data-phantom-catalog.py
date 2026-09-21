#!/usr/bin/env python3
"""幻境目录静态提取器的合成测试；不读取账号、不联网、不执行 JavaScript。"""

import json
from pathlib import Path
import runpy
import unittest


generator = runpy.run_path(str(Path(__file__).with_name('generate-personal-data-phantom-catalog.py')))
LiteralReader = generator['LiteralReader']
extract_catalog = generator['extract_catalog']


def fixture():
    def item(identifier, category=2):
        return dict(ItemId=identifier, Name='合成名称 " Unicode α', Icon=30704, ItemUICategory=category)
    arrays = {variable: [item(identifier) for identifier in range(first, first + 22)]
              for variable, _, first in generator['STAGES']}
    arrays['s'] = [item(47740, 61), item(47741, 44), item(47743, 44)] + [item(identifier, 63) for identifier in range(47744, 47750)]
    source = '"2cff":function(e,t,i){var n=[]'
    for variable in ['a', 'r', 'o', 's', 'l', 'c']:
        literal = json.dumps(arrays[variable], ensure_ascii=False, separators=(',', ':'))
        source += ',' + variable + '=' + literal.replace('"ItemId":51000', '"ItemId":51e3')
    demi = [dict(id=identifier, name='合成消幻晶', icon=26229) for identifier in [50974, 50975, 50976]]
    return source + '},"9da6":function(e,t,i){var H=' + json.dumps(demi) + ',W={}}'


class PhantomCatalogGeneratorTest(unittest.TestCase):
    def test_scientific_notation_is_exact_and_never_truncated(self):
        self.assertEqual({'ItemId': 51000, 'Other': 10000}, LiteralReader('{ItemId:51e3,Other:1e4}').value())
        self.assertEqual(2147483647, LiteralReader('2.147483647e9').value())
        self.assertEqual(-2147483648, LiteralReader('-2147483648').value())

    def test_nonintegral_nonfinite_out_of_range_and_executable_tokens_are_rejected(self):
        for source in ['1e-3', '2147483648', '-2147483649', 'NaN', 'Infinity', '0xC738',
                       '[1+2]', '[1e3+4]', '[function(){return 51000}()]', '[...items]', '`name`', 'window.data']:
            with self.subTest(source=source), self.assertRaises(ValueError):
                LiteralReader(source).value()

    def test_duplicate_fields_and_truncated_literals_are_rejected(self):
        for source in ['{ItemId:1,ItemId:2}', '[{ItemId:1}', '{ItemId:', '[1,]']:
            with self.subTest(source=source), self.assertRaises(ValueError):
                LiteralReader(source).value()

    def test_every_stage_preserves_22_items_including_51000(self):
        result = extract_catalog(fixture())
        self.assertEqual(110, len(result['weapons']))
        for _, stage, first in generator['STAGES']:
            self.assertEqual(list(range(first, first + 22)), [row['itemId'] for row in result['weapons'] if row['stage'] == stage])
        self.assertIn(51000, [row['itemId'] for row in result['weapons']])
        self.assertEqual('合成名称 " Unicode α', result['weapons'][0]['name'])

    def test_only_the_six_reviewed_soul_crystals_and_three_demiatma_are_included(self):
        result = extract_catalog(fixture())
        self.assertEqual(list(range(47744, 47750)), [row['itemId'] for row in result['soulCrystals']])
        self.assertEqual([50974, 50975, 50976], [row['itemId'] for row in result['demiatma']])

    def test_missing_or_duplicate_ids_and_unreviewed_material_changes_are_rejected(self):
        for source in [fixture().replace('"ItemId":51e3', '"ItemId":51001'),
                       fixture().replace('"ItemId":47744', '"ItemId":47740'),
                       fixture().replace('"id": 50974', '"id": 50975')]:
            with self.subTest(), self.assertRaises(ValueError):
                extract_catalog(source)

    def test_challenge_html_and_changed_source_require_review(self):
        for source in [b'<html>challenge</html>', b'(window["webpackJsonp"] = synthetic)']:
            with self.subTest(), self.assertRaises(ValueError):
                generator['document'](source, '2026-09-20')


if __name__ == '__main__':
    unittest.main()

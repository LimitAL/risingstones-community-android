# Glamour detail metadata

`GlamourApiService.fetchDetail` maps metadata from the official
`api/home/glamour/glamourDetail` response. The detail is sufficient for these
fields; callers do not need a listing summary or a job-name catalog.

| Response field | Public contract | Mapping |
| --- | --- | --- |
| `job_ids` | `GlamourDetail.jobs: List<GlamourJob>` | Objects with a parseable `id` and a nonempty string `name`; order retained. |
| `gender_ids` | `GlamourDetail.genderIds: List<Int>` | Numeric and integer-string entries; order and unknown IDs retained. |
| `tags[].category_code` | `GlamourDetailTag.categoryCode: String?` | Raw string, including unknown codes; missing/null is `null`. |

The response shape `job_ids: [{"id":24,"name":"白魔法师"},28]` produces only
the named job. Bare IDs, missing IDs, missing names, empty names and nonstring
names are omitted. Names are not trimmed by this mapping. Applicable jobs and
genders are declared by the author for the glamour, rather than the character's
current job or gender. Gender IDs 1 and 2 mean male and female; future IDs are
not rejected. `skin_tone` is a stable tag category code; consumers may recognize
it without matching localized category names.

Job IDs and gender IDs distinguish JSON numbers from JSON strings. Numeric
fractions truncate toward zero (`2.9` becomes `2`); strings must encode an
integer (`"24"` is accepted, `"24.9"` and `"2.4e1"` are omitted). This parsing
is scoped to these two detail fields; existing shared integer helpers retain
their behavior.

Absent/null arrays and legacy ID-only job arrays remain valid. Existing
camelCase aliases (`jobIds`, `genderIds`, `categoryCode`) are accepted using the
same non-null snake_case-first precedence as other detail fields. Malformed
optional entries are skipped without discarding the detail. Existing tag naming
and sorting, equipment, accessory and author-placeholder behavior is retained.

The new properties are appended with defaults (`emptyList()`, `emptyList()`,
`null`). Previous Kotlin source constructor calls, positional arguments,
destructuring positions and named `copy` calls retain their meaning. The data
classes' generated JVM constructor/copy signatures change, so already compiled
consumers must be rebuilt with the matching domain and data artifacts. No
release version is changed here.

Focused verification uses `:glamour-domain:testDebugUnitTest` and
`:glamour-data:testDebugUnitTest`. Independent metadata fixtures cover the
official object/ID mixture, missing/null/legacy responses, numeric strings,
aliases, unknown IDs/codes and existing detail fields. A domain test compiles
the previous positional constructors and a named copy call. Before adopting
published artifacts, publish the matching SDK modules and compile the
independent Maven consumer and host application against that version.

## Follow-up backlog

The existing tag comparator uses tag ID to break equal category/tag sort ties.
An input-order tie policy remains a separate compatibility decision; this
metadata change retains the existing comparator.

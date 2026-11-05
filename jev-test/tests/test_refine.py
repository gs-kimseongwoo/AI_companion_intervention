from __future__ import annotations

import unittest

from jev_pippa.refine_dataset import SCHEMA_VERSION, refine_dataset


class RefineTests(unittest.TestCase):
    def test_converts_source_fields_to_standard_schema(self) -> None:
        raw = {
            "intro": "[배경]\n비가 온다.",
            "turn_count": 2,
            "turns": [
                {
                    "turn": 1,
                    "user_response": "누구야?",
                    "ai_response": (
                        '[엘레노어]\n"나야."\n'
                        '[엘레노어]\n"들어가도 돼?"'
                    ),
                },
                {
                    "turn": 2,
                    "user_response": "잠깐.",
                    "ai_response": "",
                },
            ],
        }

        refined = refine_dataset(raw)

        self.assertEqual(refined["schema_version"], SCHEMA_VERSION)
        self.assertEqual(
            refined["preamble"],
            [{"role": "assistant", "content": "[배경]\n비가 온다."}],
        )
        self.assertEqual(
            refined["turns"][0],
            {
                "turn": 1,
                "user": "누구야?",
                "assistant": '[엘레노어]\n"나야."\n"들어가도 돼?"',
            },
        )
        self.assertEqual(refined["turns"][1]["assistant"], "")

    def test_rejects_nonstandard_array_dataset(self) -> None:
        with self.assertRaisesRegex(ValueError, "JSON object"):
            refine_dataset([])


if __name__ == "__main__":
    unittest.main()

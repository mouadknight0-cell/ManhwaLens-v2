import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('translation_audit', Path(__file__).resolve().parents[2] / 'scripts/audit_translation_report.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AuditTest(unittest.TestCase):
    def report(self, rows):
        return '\n== Translation samples JSON (includes partial captures) ==\n' + json.dumps(rows) + '\n== END OF REPORT ==\ndone\n'

    def test_versions_duplicates_and_semantics_are_not_conflated(self):
        row = {'id': 'one', 'app_version': 'new', 'source': 'D0CTOR', 'translation': 'D0CTOR طبيب'}
        result = module.audit(self.report([row, row, dict(row, app_version='old')]), 'new')
        self.assertEqual(1, result['unique_selected_samples'])
        self.assertIsNone(result['semantic_accuracy'])
        self.assertEqual({'no_technical_error': 1}, result['technical_states'])
        self.assertIn('review_mixed_alphanumeric_OCR', result['review_queue'][0]['flags'])

    def test_incomplete_report_rejected(self):
        with self.assertRaises(ValueError):
            module.audit('== Translation samples JSON ==\n[]', 'new')

    def test_empty_selected_version_is_not_success(self):
        result = module.audit(self.report([]), 'absent')
        self.assertEqual(0, result['unique_selected_samples'])
        self.assertIsNone(result['semantic_accuracy'])


if __name__ == '__main__':
    unittest.main()

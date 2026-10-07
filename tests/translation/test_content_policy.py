import collections
import contextlib
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'providers' / 'translation'))
from content_policy import canonicalize, policy_key, validate_policy
from windows_worker import LocalProvider


def policy(source='party', target='nhóm', aliases=None, profile='pc-game'):
    return {'profile': profile, 'terms': [{'source': source, 'target': target,
                                          'aliases': ['bữa tiệc'] if aliases is None else aliases}]}


class ContentPolicyTests(unittest.TestCase):
    def apply(self, source, result, selected=None):
        return canonicalize(source, result, validate_policy(selected or policy()))

    def test_source_gate_and_word_boundaries(self):
        self.assertEqual(self.apply('The party leaves.', 'Bữa tiệc rời đi.')['text'], 'nhóm rời đi.')
        for source in ('The partygoer leaves.', 'We depart.', 'The third_party leaves.'):
            reply = self.apply(source, 'Bữa tiệc rời đi.')
            self.assertEqual(reply['text'], 'Bữa tiệc rời đi.')
            self.assertEqual(reply['glossaryApplied'], 0)

    def test_negation_and_surrounding_words_preserved(self):
        reply = self.apply('Do not leave the party.', 'Đừng rời bữa tiệc, vẫn chưa xong.')
        self.assertEqual(reply['text'], 'Đừng rời nhóm, vẫn chưa xong.')

    def test_output_requires_declared_whole_alias(self):
        reply = self.apply('party', 'Không tiệc tùng nữa.')
        self.assertEqual(reply['text'], 'Không tiệc tùng nữa.')
        self.assertEqual(reply['glossaryUnresolved'], 1)
        self.assertEqual(self.apply('party', 'x_bữa tiệc!')['glossaryUnresolved'], 1)

    def test_case_and_unicode_normalization(self):
        reply = self.apply('PARTY!', 'BỮA TIỆC đã đến.')
        self.assertEqual(reply['text'], 'nhóm đã đến.')
        self.assertEqual(reply['glossaryApplied'], 1)

    def test_canonical_target_is_satisfied(self):
        reply = self.apply('party', 'Nhóm chưa đến.')
        self.assertEqual(reply['text'], 'Nhóm chưa đến.')
        self.assertEqual(reply['glossaryApplied'], 1)
        self.assertEqual(reply['glossaryUnresolved'], 0)
        self.assertFalse(reply['contextSupported'])
        self.assertEqual(reply['translationMode'], 'sentence-glossary')

    def test_no_implicit_profile_terms(self):
        for profile_name in ('general', 'technology', 'film', 'pc-game'):
            reply = canonicalize('party', 'bữa tiệc', validate_policy({'profile': profile_name, 'terms': []}))
            self.assertEqual(reply['text'], 'bữa tiệc')
            self.assertEqual(reply['glossaryApplied'], 0)

    def test_multiple_occurrences_count_terms_not_tokens(self):
        reply = self.apply('party party', 'bữa tiệc và bữa tiệc')
        self.assertEqual(reply['text'], 'nhóm và nhóm')
        self.assertEqual(reply['glossaryApplied'], 1)

    def test_single_pass_no_cascades_even_for_unvalidated_helper_input(self):
        selected = {'profile': 'general', 'terms': [
            {'source': 'one', 'target': 'second', 'aliases': ['first']},
            {'source': 'two', 'target': 'third', 'aliases': ['second']}]}
        self.assertEqual(canonicalize('one two', 'first second', selected)['text'], 'second third')
        with self.assertRaises(ValueError):
            validate_policy(selected)

    def test_policy_cache_key_includes_all_settings(self):
        original = policy_key(validate_policy(policy()))
        for selected in (policy(profile='film'), policy(target='đội'), policy(aliases=['tiệc'])):
            self.assertNotEqual(original, policy_key(validate_policy(selected)))

    def test_invalid_profiles_shapes_and_limits(self):
        for selected in ([], {}, {'profile': [], 'terms': []}, {'profile': 'console', 'terms': []},
                         {'profile': 'general', 'terms': [], 'context': 'ignored'},
                         {'profile': 'general', 'terms': [policy()['terms'][0]] * 33},
                         policy(aliases=['a', 'b', 'c', 'd', 'e'])):
            with self.subTest(selected=selected), self.assertRaises(ValueError):
                validate_policy(selected)

    def test_controls_empty_and_oversized(self):
        for value in ('', '  ', 'x' * 81, 'hello\nworld', 'hello\tworld', 'hello\0world', 'x\u2028y', 'x\u200by'):
            for field in ('source', 'target', 'aliases'):
                selected = policy()
                selected['terms'][0][field] = [value] if field == 'aliases' else value
                with self.subTest(value=value, field=field), self.assertRaises(ValueError):
                    validate_policy(selected)

    def test_duplicate_and_overlapping_phrases_rejected(self):
        for second in ({'source': 'PARTY', 'target': 'đội', 'aliases': []},
                       {'source': 'party leader', 'target': 'đội', 'aliases': []},
                       {'source': 'group', 'target': 'nhóm lớn', 'aliases': []},
                       {'source': 'group', 'target': 'đội', 'aliases': ['BỮA TIỆC']}):
            selected = policy()
            selected['terms'].append(second)
            with self.subTest(second=second), self.assertRaises(ValueError):
                validate_policy(selected)

    def test_trim_and_maximum_valid_bounds(self):
        selected = {'profile': 'general', 'terms': [
            {'source': 'source%02d' % index, 'target': 'target%02d' % index,
             'aliases': ['alias%02d_%d' % (index, suffix) for suffix in range(4)]}
            for index in range(32)]}
        self.assertEqual(len(validate_policy(selected)['terms']), 32)
        self.assertEqual(validate_policy(policy(source='  party  '))['terms'][0]['source'], 'party')
        self.assertEqual(validate_policy(None), {'profile': 'general', 'terms': []})

    def test_validation_precedes_model_loading(self):
        provider = object.__new__(LocalProvider)
        provider.load_mt = lambda: self.fail('Invalid policy loaded model')
        with self.assertRaises(ValueError):
            provider.execute('translate', 'party', content_policy={'profile': 'bad', 'terms': []})

    def test_validate_policy_operation_needs_no_models_or_text(self):
        provider = object.__new__(LocalProvider)
        self.assertEqual(provider.execute('validate-policy', '', content_policy=policy()), {'text': 'Policy valid'})
        self.assertEqual(provider.execute('validate-policy', ''), {'text': 'Policy valid'})
        selected = policy(aliases=['nhóm'])
        with self.assertRaises(ValueError):
            provider.execute('validate-policy', '', content_policy=selected)

    def test_worker_cache_separates_policies_and_returns_copies(self):
        provider = object.__new__(LocalProvider)
        provider.cache = collections.OrderedDict()
        provider.load_mt = lambda: None
        class Tokenizer:
            supported_language_codes = []
            def __call__(self, *args, **kwargs):
                return {'input_ids': types.SimpleNamespace(shape=(1, 2))}
            def decode(self, *args, **kwargs):
                return 'Bữa tiệc đi rồi.'
        class Model:
            calls = 0
            def generate(self, **kwargs):
                self.calls += 1
                return [[1, 2]]
        provider.tokenizer, provider.mt = Tokenizer(), Model()
        fake_torch = types.SimpleNamespace(inference_mode=contextlib.nullcontext)
        with patch.dict(sys.modules, {'torch': fake_torch}):
            first = provider.execute('translate', 'party', content_policy=policy())
            self.assertEqual(first['text'], 'nhóm đi rồi.')
            first['text'] = 'mutated'
            self.assertEqual(provider.execute('translate', 'party', content_policy=policy())['text'], 'nhóm đi rồi.')
            self.assertEqual(provider.execute('translate', 'party', content_policy=policy(target='đội'))['text'], 'đội đi rồi.')
            self.assertEqual(provider.execute('translate', 'party')['text'], 'Bữa tiệc đi rồi.')
        self.assertEqual(provider.mt.calls, 3)
        self.assertEqual(len(provider.cache), 3)


if __name__ == '__main__':
    unittest.main()

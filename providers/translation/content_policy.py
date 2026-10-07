"""Bounded, request-scoped terminology; not contextual model prompting.

Only user-declared output aliases can be canonicalized, and only when the
corresponding source phrase is present. No term is inferred from a profile.
"""
import json
import re
import unicodedata

PROFILES = frozenset(('general', 'technology', 'film', 'pc-game'))


def _phrase(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 80:
        raise ValueError('Invalid terminology phrase')
    if any(unicodedata.category(character).startswith('C') or character in '\r\n\u2028\u2029'
           for character in value):
        raise ValueError('Invalid terminology control')
    result = unicodedata.normalize('NFC', value.strip())
    if not result or len(result) > 80:
        raise ValueError('Empty terminology phrase')
    return result


def _pattern(phrase):
    return re.compile(r'(?<!\w)' + re.escape(phrase) + r'(?!\w)', re.IGNORECASE)


def _overlap(left, right):
    return bool(_pattern(left).search(right) or _pattern(right).search(left))


def validate_policy(value=None):
    if value is None:
        value = {'profile': 'general', 'terms': []}
    if not isinstance(value, dict) or set(value) != {'profile', 'terms'}:
        raise ValueError('Invalid content policy')
    if not isinstance(value['profile'], str) or value['profile'] not in PROFILES:
        raise ValueError('Invalid content profile')
    terms = value['terms']
    if not isinstance(terms, list) or len(terms) > 32:
        raise ValueError('Terminology count exceeds bound')
    validated, sources, outputs = [], [], []
    for term in terms:
        if not isinstance(term, dict) or set(term) != {'source', 'target', 'aliases'}:
            raise ValueError('Invalid terminology entry')
        source, target = _phrase(term['source']), _phrase(term['target'])
        if not isinstance(term['aliases'], list) or len(term['aliases']) > 4:
            raise ValueError('Alias count exceeds bound')
        aliases = [_phrase(alias) for alias in term['aliases']]
        if any(_overlap(source, previous) for previous in sources):
            raise ValueError('Ambiguous source terminology')
        local_outputs = [target] + aliases
        for index, phrase in enumerate(local_outputs):
            if any(_overlap(phrase, previous) for previous in outputs + local_outputs[:index]):
                raise ValueError('Ambiguous output terminology')
        sources.append(source)
        outputs.extend(local_outputs)
        validated.append({'source': source, 'target': target, 'aliases': aliases})
    return {'profile': value['profile'], 'terms': validated}


def policy_key(policy):
    """Include every canonical policy field, not just a domain label."""
    return json.dumps(policy, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def canonicalize(source, translated, policy):
    source = unicodedata.normalize('NFC', source)
    translated = unicodedata.normalize('NFC', translated)
    active = [term for term in policy['terms'] if _pattern(term['source']).search(source)]
    aliases = [(alias, term['target']) for term in active for alias in term['aliases']]
    aliases.sort(key=lambda item: len(item[0]), reverse=True)
    # Match against the original translation once. New target text is never scanned.
    if aliases:
        pattern = re.compile(r'(?<!\w)(?:' + '|'.join(re.escape(item[0]) for item in aliases) + r')(?!\w)', re.IGNORECASE)
        def replace(match):
            matched = match.group(0)
            for alias, target in aliases:
                if _pattern(alias).fullmatch(matched):
                    return target
            return matched
        result = pattern.sub(replace, translated)
    else:
        result = translated
    applied = sum(bool(_pattern(term['target']).search(translated) or
                       any(_pattern(alias).search(translated) for alias in term['aliases'])) for term in active)
    return {'text': result, 'translationMode': 'sentence-glossary',
            'glossaryApplied': applied, 'glossaryUnresolved': len(active) - applied,
            'contextSupported': False}

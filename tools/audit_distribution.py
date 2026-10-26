import argparse
import datetime
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def class_strings(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid JVM class header')
    count = struct.unpack_from('>H', data, 8)[0]
    pool = {}
    literal_indices = []
    offset = 10
    index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            pool[index] = data[offset:offset + length].decode('utf8', errors='replace')
            offset += length
        else:
            if tag == 8:
                literal_indices.append(struct.unpack_from('>H', data, offset)[0])
            offset += sizes[tag]
            if tag in (5, 6):
                index += 1
        index += 1
    return set(pool.values()), {pool[index] for index in literal_indices}


def manifest_keys(text, section):
    match = re.search(r'^' + re.escape(section) + r':\s*\n((?:[ \t].*\n|\n)*)', text, re.M)
    return set(re.findall(r'^  ([\w.*-]+):', match.group(1), re.M)) if match else set()


def manifest_aliases(text):
    values = set()
    for group in re.findall(r'^\s+aliases:\s*\[([^\]]*)\]', text, re.M):
        values.update(value.strip().strip("'\"") for value in group.split(',') if value.strip())
    return values


def inventory(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP member names')
        members = {name: archive.read(name) for name in names if not name.endswith('/')}
    classes = {name for name in members if name.endswith('.class')}
    strings = set()
    literals = set()
    for name in classes:
        values, constants = class_strings(members[name])
        strings.update(values)
        literals.update(constants)
    manifest = members.get('plugin.yml', b'').decode('utf8')
    return members, classes, strings, literals, manifest


def main():
    parser = argparse.ArgumentParser(description='Inspect an independently built distribution without executing either JAR.')
    parser.add_argument('reference', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    original, original_classes, original_strings, original_literals, original_manifest = inventory(args.reference)
    candidate, classes, strings, literals, manifest = inventory(args.candidate)
    hashes = {}
    for name, data in original.items():
        if data:
            hashes.setdefault(digest(data), []).append(name)
    identical = [{'candidate': name, 'reference': hashes[digest(data)]}
                 for name, data in sorted(candidate.items()) if data and digest(data) in hashes]
    forbidden = re.compile(r'itemsadder|lonedev|dev[/.]lone(?:[/.]|$)|devs\.beer|pstats', re.I)
    traces = []
    for name, data in sorted(candidate.items()):
        if forbidden.search(name):
            traces.append({'member': name, 'location': 'member name'})
        if name.endswith('.class'):
            values, _ = class_strings(data)
            for value in sorted(values):
                if forbidden.search(value):
                    traces.append({'member': name, 'value': value})
        elif name.endswith(('.yml', '.yaml', '.json', '.xml', '.MF', '.properties', '.txt')):
            for number, line in enumerate(data.decode('utf8', errors='replace').splitlines(), 1):
                if forbidden.search(line):
                    traces.append({'member': name, 'line': number, 'value': line})
    common_messages = sorted(value for value in literals & original_literals
                             if len(value) >= 24 and re.search(r'[A-Za-z]{3}\s+[A-Za-z]{3}', value))
    permissions = sorted(manifest_keys(manifest, 'permissions') & manifest_keys(original_manifest, 'permissions'))
    commands = sorted(manifest_keys(manifest, 'commands') & manifest_keys(original_manifest, 'commands'))
    command_surface = manifest_keys(manifest, 'commands') | manifest_aliases(manifest)
    original_surface = manifest_keys(original_manifest, 'commands') | manifest_aliases(original_manifest)
    aliases = sorted(command_surface & original_surface)
    foreign_classes = sorted(name for name in classes if not name.startswith('io/github/lino9999/blocksadder/'))
    leaked_inputs = sorted(name for name in candidate if re.search(r'(?:^|/)(?:\.work|Plugin Originale|decompiled|test|tests)(?:/|$)', name, re.I))
    report = {
        'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'method': 'Offline ZIP bytes, all JVM constant pools, string literals and declared manifest keys. Neither artifact was executed.',
        'reference': {'sha256': digest(args.reference.read_bytes()), 'files': len(original), 'classes': len(original_classes)},
        'candidate': {'sha256': digest(args.candidate.read_bytes()), 'files': len(candidate), 'classes': len(classes)},
        'identical_nonempty_files': identical,
        'identical_class_paths': sorted(classes & original_classes),
        'foreign_class_paths': foreign_classes,
        'reference_branding_traces': traces,
        'shared_declared_permissions': permissions,
        'shared_declared_commands': commands,
        'shared_command_or_alias_names': aliases,
        'shared_long_message_literals': common_messages,
        'analysis_or_test_inputs_in_jar': leaked_inputs,
        'bundled_yaml_files': sorted(name for name in candidate if name.endswith(('.yml', '.yaml'))),
        'limitations': [
            'Different bytes, names and literals do not prove independent authorship of every algorithm.',
            'Common API names, Minecraft identifiers and short vocabulary are intentionally not classified as copied text.',
            'This report does not determine copyright ownership or guarantee marketplace approval.',
            'Behavioral equivalence and supported-version acceptance require separate runtime evidence.'
        ]
    }
    report['passed'] = not any([identical, report['identical_class_paths'], foreign_classes, traces,
                                permissions, commands, aliases, common_messages, leaked_inputs])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf8')
    print(json.dumps(report, indent=2, ensure_ascii=True))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()

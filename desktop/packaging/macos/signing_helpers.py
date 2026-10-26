"""Fail-closed classifier for mounted macOS bundle signing diagnostics."""
import sys
from pathlib import Path


def classify_codesign(dv_status, output, verify_status):
    if 'Authority=Developer ID' in output or 'Authority=Apple' in output:
        raise ValueError('Unexpected distribution signing identity; investigate build host')
    if dv_status == 0 and 'Signature=adhoc' in output and verify_status == 0:
        return 'ad-hoc signature (codesign -dv and verify succeeded)'
    if dv_status != 0 and 'not signed at all' in output and verify_status != 0:
        return 'unsigned app bundle (codesign -dv reported not signed)'
    raise ValueError('unclassified signing state or failed verification')


def main(root, dv_status, verify_status):
    root = Path(root)
    try:
        observed = classify_codesign(int(dv_status), (root / 'codesign-dv.txt').read_text(errors='replace'), int(verify_status))
    except ValueError as exc:
        (root / 'signing-status.txt').open('a').write('observed=unclassified; ' + str(exc) + '\n')
        raise SystemExit(str(exc))
    (root / 'signing-status.txt').open('a').write('observed=' + observed + '\n')
    print('observed signing state:', observed)


if __name__ == '__main__':
    main(*sys.argv[1:])

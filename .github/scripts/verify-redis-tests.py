#!/usr/bin/env python3
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

reports = list(Path(sys.argv[1]).glob('*RedisSessionStoreTest*.xml'))
if not reports:
    raise SystemExit('Missing Redis integration test report')
for report in reports:
    root = ET.parse(report).getroot()
    if int(root.attrib.get('tests', 0)) == 0 or any(
        int(root.attrib.get(key, 0)) for key in ('failures', 'errors', 'skipped')
    ):
        raise SystemExit('Redis integration tests must run and pass: ' + str(report))
print('Redis integration tests ran without skips.')

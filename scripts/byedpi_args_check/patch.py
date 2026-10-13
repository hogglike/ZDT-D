#!/usr/bin/env python3
"""Add an opt-in parser-only check to pinned ByeDPI; normal execution unchanged."""
from pathlib import Path
import sys
path=Path(sys.argv[1])/'main.c'
s=path.read_text()
old='    if (all_limited) {'
assert s.count(old)==1, 'Pinned ByeDPI parser changed'
s=s.replace(old,'''    if (getenv("ZDT_CHECK_ARGS") && !strcmp(getenv("ZDT_CHECK_ARGS"), "1")) {
        puts("ZDT_ARGS_OK");
        clear_params();
        return 0;
    }
'''+old)
path.write_text(s)

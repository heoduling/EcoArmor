"""Extract only this fork's compile-time helper from its patched libreforge dependency.

Usage: python prepare_scheduler_api.py /path/to/unshaded-common.jar /path/to/scheduler-api.jar
No implementation from this temporary compileOnly JAR is bundled in EcoArmor.
"""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import sys

source, destination = map(Path, sys.argv[1:])
name = "com/willfp/libreforge/SchedulerHelper.class"
with ZipFile(source) as archive:
    content = archive.read(name)
with ZipFile(destination, "w", ZIP_DEFLATED) as archive:
    archive.writestr(name, content)
print(destination)

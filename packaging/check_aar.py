#!/usr/bin/env python3
"""Refuse an Android AAR that would crash every APK built against it.

    python3 packaging/check_aar.py mobile/ciris-client-0.5.196.aar

WHY THIS EXISTS

`ciris-client-0.5.195.aar` shipped 5,820 `ai/ciris/mobile` classes and ZERO
`ai/ciris/api`. Every APK built against it died on launch:

    java.lang.NoClassDefFoundError: Lai/ciris/api/apis/AgentApi;
        at CIRISApiClient.<init>(CIRISApiClient.kt:446)
        at MainActivity.onCreate(MainActivity.kt:136)

on the startup path, so not a rare corner — every launch (CIRISClient#25).

An Android AAR never contains its project dependencies' classes. Gradle expects
consumers to resolve them from a repository using the POM beside the artifact.
This AAR is published as a bare file on a GitHub release and dropped into
`apps/android/libs/`, so there is no POM and no repository, and
`implementation(project(":generated-api"))` could never reach a consumer.

WHY IT CHECKS THE ARTIFACT AND NOT THE BUILD

The broken release was a `BUILD SUCCESSFUL` producing a 16 MB file. Neither the
exit code nor the size distinguished it from a good one. The only thing that
does is opening it and looking for the class the crash named.
"""

from __future__ import annotations

import io
import sys
import zipfile

#: The class NoClassDefFoundError actually named. Present iff the merge worked.
CANARY = "ai/ciris/api/apis/AgentApi.class"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    path = argv[1]

    try:
        with zipfile.ZipFile(path) as aar:
            if "classes.jar" not in aar.namelist():
                print(f"::error::{path} has no classes.jar")
                return 1
            jar_bytes = aar.read("classes.jar")
    except (OSError, zipfile.BadZipFile) as e:
        print(f"::error::cannot read {path}: {e}")
        return 1

    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        names = jar.namelist()

    mobile = sum(1 for n in names if n.startswith("ai/ciris/mobile/") and n.endswith(".class"))
    api = sum(1 for n in names if n.startswith("ai/ciris/api/") and n.endswith(".class"))

    print(f"  {path}")
    print(f"    ai/ciris/mobile : {mobile}")
    print(f"    ai/ciris/api    : {api}")

    if mobile == 0:
        print("::error::the AAR carries no ai/ciris/mobile classes — :shared is missing")
        return 1
    if api == 0:
        print(
            "::error::the AAR carries no ai/ciris/api classes; every APK built "
            "against it dies in onCreate (CIRISClient#25)"
        )
        return 1
    if CANARY not in names:
        print(f"::error::{CANARY} is absent — the exact class the crash named")
        return 1

    print("    both modules present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

# Copyright 2017 Google Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
################################################################################
"""Generate Java test rules from given test_files.

Instead of having to create one test rule per test in the BUILD file, this rule
provides a handy way to create a bunch of test rules for the specified test
files.

"""

load("@rules_java//java:defs.bzl", "java_test")

def gen_java_test_rules(
        test_files,
        deps,
        data = [],
        exclude_tests = [],
        default_test_size = "small",
        small_tests = [],
        medium_tests = [],
        large_tests = [],
        enormous_tests = [],
        flaky_tests = [],
        manual_tests = [],
        notsan_tests = [],
        no_rbe_tests = [],
        resources = [],
        tags = [],
        prefix = "",
        jvm_flags = [],
        args = [],
        visibility = None,
        shard_count = 1):
    for test in _get_test_names(test_files):
        if test in exclude_tests:
            continue
        test_size = default_test_size
        if test in small_tests:
            test_size = "small"
        if test in medium_tests:
            test_size = "medium"
        if test in large_tests:
            test_size = "large"
        if test in enormous_tests:
            test_size = "enormous"
        manual = []
        if test in manual_tests:
            manual = ["manual"]
        notsan = []
        if test in notsan_tests:
            notsan = ["notsan"]
        no_rbe = []
        if test in no_rbe_tests:
            no_rbe = ["no_rbe"]
        flaky = 0
        if (test in flaky_tests) or ("flaky" in tags):
            flaky = 1
        java_class = _package_from_path(
            native.package_name() + "/" + _strip_right(test, ".java"),
        )
        java_test(
            name = prefix + test,
            runtime_deps = deps,
            data = data,
            resources = resources,
            size = test_size,
            jvm_flags = jvm_flags,
            args = args,
            flaky = flaky,
            tags = tags + manual + notsan + no_rbe,
            test_class = java_class,
            visibility = visibility,
            shard_count = shard_count,
        )

def _get_test_names(test_files):
    test_names = []
    for test_file in test_files:
        if not test_file.endswith("Test.java"):
            continue
        test_names += [test_file[:-5]]
    return test_names

def _package_from_path(package_path, src_impls = None):
    src_impls = src_impls or ["src/test/java/", "javatests/", "java/"]
    for src_impl in src_impls:
        if not src_impl.endswith("/"):
            src_impl += "/"
        index = _index_of_end(package_path, src_impl)
        if index >= 0:
            package_path = package_path[index:]
            break
    return package_path.replace("/", ".")

def _strip_right(s, suffix):
    if s.endswith(suffix):
        return s[0:len(s) - len(suffix)]
    else:
        return s

def _index_of_end(s, part):
    index = s.find(part)
    if index >= 0:
        return index + len(part)
    return -1

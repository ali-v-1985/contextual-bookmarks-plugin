#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="${script_dir}/verify-plugin-archive.sh"
test_root="$(mktemp -d)"
trap 'rm -rf "${test_root}"' EXIT

make_archive() {
    local source_dir="$1"
    local archive="$2"
    (
        cd "${source_dir}"
        zip -qr "${archive}" .
    )
}

expect_failure() {
    local expected_message="$1"
    local distribution_dir="$2"
    local output

    if output="$("${verifier}" "${distribution_dir}" 2>&1)"; then
        echo "Expected archive verification to fail for ${distribution_dir}" >&2
        exit 1
    fi
    if [[ "${output}" != *"${expected_message}"* ]]; then
        echo "Archive verification failed without the expected message: ${expected_message}" >&2
        echo "${output}" >&2
        exit 1
    fi
}

valid_source="${test_root}/valid-source"
valid_distribution="${test_root}/valid-distribution"
valid_jar_source="${test_root}/valid-jar-source"
mkdir -p "${valid_source}/plugin/lib" "${valid_distribution}" "${valid_jar_source}/META-INF"
printf '<idea-plugin />' > "${valid_jar_source}/META-INF/plugin.xml"
make_archive "${valid_jar_source}" "${valid_source}/plugin/lib/plugin.jar"
make_archive "${valid_source}" "${valid_distribution}/plugin.zip"
"${verifier}" "${valid_distribution}" >/dev/null

forbidden_source="${test_root}/forbidden-source"
forbidden_distribution="${test_root}/forbidden-distribution"
mkdir -p "${forbidden_source}/plugin" "${forbidden_distribution}"
printf 'secret' > "${forbidden_source}/plugin/.env"
make_archive "${forbidden_source}" "${forbidden_distribution}/plugin.zip"
expect_failure "Plugin archive contains forbidden files" "${forbidden_distribution}"

nested_forbidden_source="${test_root}/nested-forbidden-source"
nested_forbidden_jar_source="${test_root}/nested-forbidden-jar-source"
nested_forbidden_distribution="${test_root}/nested-forbidden-distribution"
mkdir -p \
    "${nested_forbidden_source}/plugin/lib" \
    "${nested_forbidden_jar_source}/config/.env" \
    "${nested_forbidden_distribution}"
printf 'private key' > "${nested_forbidden_jar_source}/config/PRIVATE.PEM"
printf 'private key' > "${nested_forbidden_jar_source}/config/signing.key"
printf 'certificate' > "${nested_forbidden_jar_source}/config/chain.crt"
printf 'certificate' > "${nested_forbidden_jar_source}/config/issuer.cer"
printf 'secret' > "${nested_forbidden_jar_source}/config/.env/secret"
printf 'private key' > "${nested_forbidden_jar_source}/config/.key"
printf 'private key' > "${nested_forbidden_jar_source}/config/.pem"
make_archive \
    "${nested_forbidden_jar_source}" \
    "${nested_forbidden_source}/plugin/lib/plugin.jar"
make_archive "${nested_forbidden_source}" "${nested_forbidden_distribution}/plugin.zip"
expect_failure "config/PRIVATE.PEM" "${nested_forbidden_distribution}"
expect_failure "config/signing.key" "${nested_forbidden_distribution}"
expect_failure "config/chain.crt" "${nested_forbidden_distribution}"
expect_failure "config/issuer.cer" "${nested_forbidden_distribution}"
expect_failure "config/.env/secret" "${nested_forbidden_distribution}"
expect_failure "config/.key" "${nested_forbidden_distribution}"
expect_failure "config/.pem" "${nested_forbidden_distribution}"

nested_source="${test_root}/nested-source"
nested_source_jar="${test_root}/nested-source-jar"
nested_source_distribution="${test_root}/nested-source-distribution"
mkdir -p \
    "${nested_source}/plugin/lib" \
    "${nested_source_jar}/me/a1i" \
    "${nested_source_distribution}"
printf 'class Foo' > "${nested_source_jar}/me/a1i/Foo.kt"
printf 'class Bar {}' > "${nested_source_jar}/me/a1i/Bar.java"
printf '<module />' > "${nested_source_jar}/contextual-bookmarks.iml"
make_archive "${nested_source_jar}" "${nested_source}/plugin/lib/plugin.jar"
make_archive "${nested_source}" "${nested_source_distribution}/plugin.zip"
expect_failure "me/a1i/Foo.kt" "${nested_source_distribution}"
expect_failure "me/a1i/Bar.java" "${nested_source_distribution}"
expect_failure "contextual-bookmarks.iml" "${nested_source_distribution}"

corrupt_distribution="${test_root}/corrupt-distribution"
mkdir -p "${corrupt_distribution}"
printf 'not a ZIP archive' > "${corrupt_distribution}/plugin.zip"
expect_failure "Plugin archive is not valid and readable" "${corrupt_distribution}"

echo "Archive verifier regression tests passed"

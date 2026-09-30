#!/usr/bin/env bash

set -euo pipefail

distribution_dir="${1:-build/distributions}"
maximum_size_bytes=$((20 * 1024 * 1024))
maximum_nested_archives=500
maximum_nesting_depth=5
temporary_dir="$(mktemp -d)"
trap 'rm -rf "${temporary_dir}"' EXIT
nested_archive_count=0

inspect_archive() {
    local archive_path="$1"
    local archive_label="$2"
    local nesting_depth="$3"
    local archive_entries
    local forbidden_entries
    local forbidden_pattern
    local grep_status

    if ((nesting_depth > maximum_nesting_depth)); then
        echo "Plugin archive nesting exceeds ${maximum_nesting_depth} levels at ${archive_label}" >&2
        exit 1
    fi
    if ! unzip -tqq "${archive_path}"; then
        echo "Plugin archive is not valid and readable: ${archive_label}" >&2
        exit 1
    fi
    if ! archive_entries="$(unzip -Z1 "${archive_path}")"; then
        echo "Could not list plugin archive entries: ${archive_label}" >&2
        exit 1
    fi

    forbidden_pattern='(^|/)(\.env($|[./])|\.idea(/|$)|[^/]*\.(jks|keystore|p12|pfx|pem|key|crt|cer|kt|kts|java|iml|ipr|iws)$|src(/|$)|tests?(/|$))'
    if forbidden_entries="$(grep -Ei "${forbidden_pattern}" <<< "${archive_entries}")"; then
        echo "Plugin archive contains forbidden files in ${archive_label}:" >&2
        echo "${forbidden_entries}" >&2
        exit 1
    else
        grep_status=$?
        if [[ ${grep_status} -ne 1 ]]; then
            echo "Could not inspect plugin archive entries: ${archive_label}" >&2
            exit "${grep_status}"
        fi
    fi

    local entry
    local lowercase_entry
    local nested_archive
    while IFS= read -r entry; do
        lowercase_entry="$(printf '%s' "${entry}" | tr '[:upper:]' '[:lower:]')"
        case "${lowercase_entry}" in
            *.jar | *.zip)
                nested_archive_count=$((nested_archive_count + 1))
                if ((nested_archive_count > maximum_nested_archives)); then
                    echo "Plugin ZIP contains more than ${maximum_nested_archives} nested archives" >&2
                    exit 1
                fi
                nested_archive="${temporary_dir}/nested-${nested_archive_count}.zip"
                if ! unzip -p "${archive_path}" "${entry}" > "${nested_archive}"; then
                    echo "Could not read nested archive ${archive_label}!/${entry}" >&2
                    exit 1
                fi
                inspect_archive "${nested_archive}" "${archive_label}!/${entry}" "$((nesting_depth + 1))"
                ;;
        esac
    done <<< "${archive_entries}"
}

archives=()
while IFS= read -r archive; do
    archives+=("${archive}")
done < <(find "${distribution_dir}" -maxdepth 1 -type f -name '*.zip' ! -name '*-signed.zip' -print | sort)

if [[ ${#archives[@]} -ne 1 ]]; then
    echo "Expected exactly one unsigned plugin ZIP in ${distribution_dir}, found ${#archives[@]}" >&2
    exit 1
fi

archive="${archives[0]}"
archive_size="$(wc -c < "${archive}" | tr -d ' ')"
if ((archive_size > maximum_size_bytes)); then
    echo "Plugin ZIP is ${archive_size} bytes; limit is ${maximum_size_bytes} bytes" >&2
    exit 1
fi

inspect_archive "${archive}" "${archive}" 0

echo "Verified ${archive} (${archive_size} bytes)"

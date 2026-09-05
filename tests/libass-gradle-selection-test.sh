#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root_wrapper="$project_dir/../../gradlew"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

init_script="$temporary_dir/assert-libass-selection.gradle"
cat > "$init_script" <<'EOF'
import org.gradle.api.artifacts.ProjectDependency

gradle.projectsEvaluated {
    def target = gradle.rootProject.project(':lib_ass_kt')
    def projectDependency = target.configurations.implementation.dependencies.find { dependency ->
        dependency instanceof ProjectDependency && dependency.path == ':lib_ass'
    }
    def expectsPrebuilt = target.providers.gradleProperty('libassUsePrebuilt')
        .map { value -> value.toBoolean() }
        .get()

    if (expectsPrebuilt && projectDependency != null) {
        throw new GradleException('Expected the prebuilt libass AAR, but :lib_ass is still selected.')
    }
    if (!expectsPrebuilt && projectDependency == null) {
        throw new GradleException('Expected standalone source build to select project :lib_ass.')
    }
}
EOF

(cd "$project_dir" && "$root_wrapper" -p "$project_dir" help \
    -PlibassUsePrebuilt=false \
    -I "$init_script" \
    --no-daemon \
    --no-configuration-cache \
    --console=plain)

(cd "$project_dir" && "$root_wrapper" -p "$project_dir" help \
    -PlibassUsePrebuilt=true \
    -I "$init_script" \
    --no-daemon \
    --no-configuration-cache \
    --console=plain)

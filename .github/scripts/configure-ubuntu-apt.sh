#!/bin/sh
set -eu

# CI can use this HTTPS mirror when the default Ubuntu hosts cannot resolve.
# Keep Ubuntu's suites, components and signing keys; apt still verifies them.
mirror=${1:-}
case "$mirror" in
  '') exit 0 ;;
  https://mirrors.edge.kernel.org/ubuntu) ;;
  *) echo "Unsupported Ubuntu mirror: $mirror" >&2; exit 1 ;;
esac

apt_dir=${TPV_APT_CONFIG_DIR:-/etc/apt}
ubuntu_uri='https?://(([[:alnum:]-]+\.)?archive|security)\.ubuntu\.com/ubuntu/?'
mirror_uri='mirror\+file:/etc/apt/apt-mirrors(-security)?\.txt'

found=0
for source in "$apt_dir/sources.list" "$apt_dir"/sources.list.d/*.list "$apt_dir"/sources.list.d/*.sources; do
  [ -f "$source" ] || continue
  # Only redirect mirror+file sources whose list contains Ubuntu repositories.
  for name in apt-mirrors.txt apt-mirrors-security.txt; do
    if grep -Fq "mirror+file:/etc/apt/$name" "$source"; then
      list="$apt_dir/$name"
      [ -s "$list" ] || { echo "Missing Ubuntu mirror list: $list" >&2; exit 1; }
      if grep -Ev "^[[:space:]]*($ubuntu_uri([[:space:]].*)?|#.*)?$" "$list" | grep -q .; then
        echo "Unexpected repository in $list" >&2
        exit 1
      fi
    fi
  done
  if grep -Eq "($ubuntu_uri|$mirror_uri)([[:space:]]|$)" "$source"; then
    sed -E -i \
      -e "s#${ubuntu_uri}([[:space:]]|\$)#${mirror}\\3#g" \
      -e "s#${mirror_uri}([[:space:]]|\$)#${mirror}\\2#g" \
      "$source"
    found=1
  fi
done
[ "$found" -eq 1 ] || { echo 'No supported Ubuntu apt sources found' >&2; exit 1; }

cat > "$apt_dir/apt.conf.d/80-ci-downloads" <<'EOF'
Acquire::Retries "3";
Acquire::http::Timeout "30";
Acquire::https::Timeout "30";
APT::Update::Error-Mode "any";
EOF

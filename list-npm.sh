# List top-level packages in a project, newest installed first.
# %W = birth epoch (sort key), %w = human birth time
# -n numeric, -r descending
show() {
  { find "$1" -mindepth 1 -maxdepth 1 -type d ! -name '@*' ! -name '.*'
    find "$1"/@*/ -mindepth 1 -maxdepth 1 -type d      # scoped packages: @scope/pkg
  } 2>/dev/null | xargs -r stat -c '%W|%w|%n' | sort -t'|' -k1,1nr | cut -d'|' -f2-
}
echo "Local:"
show node_modules                 # local

echo "Global:"
show "$(npm root -g)"             # global

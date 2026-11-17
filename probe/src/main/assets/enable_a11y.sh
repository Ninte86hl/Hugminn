# Adds the probe's accessibility service to the enabled list (keeps all others).
comp="$1"
cur=$(settings get secure enabled_accessibility_services)
case "$cur" in
  *"$comp"*) ;;
  null|"") settings put secure enabled_accessibility_services "$comp" ;;
  *) settings put secure enabled_accessibility_services "$cur:$comp" ;;
esac
settings put secure accessibility_enabled 1
echo "enabled_accessibility_services=$(settings get secure enabled_accessibility_services)"

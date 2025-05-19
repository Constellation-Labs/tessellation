
assemble_all() {
  sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly
}


show_time "Starting assembly"

if [[ "$INCLUDE_L0" == "false" && "$INCLUDE_L1" == "false" && "$SKIP_ASSEMBLY" == "false" ]]; then
  assemble_all
else
  missing=false

  for module in dag-l0 dag-l1 keytool wallet; do
    jar_path=$(ls -1t modules/"$module"/target/scala-2.13/tessellation-"$module"-assembly*.jar 2>/dev/null | head -n1)
    if [ -z "$jar_path" ]; then
      echo "⚠️  Missing JAR for module: $module"
      missing=true
      break
    fi
  done

  if [ "$missing" = true ]; then
    echo "▶️  One or more modules is missing. Cannot skip assembly. Running full assembly"
    assemble_all
  else
    if [ "$SKIP_ASSEMBLY" == "false" ]; then
      override_set=false
      if [ "$INCLUDE_L0" == "true" ]; then
        echo "Assembling L0"
        sbt dagL0/assembly
        override_set=true
      fi
      if [ "$INCLUDE_L1" == "true" ]; then
        echo "Assembling L1"
        sbt dagL1/assembly
        override_set=true
      fi
      if [ "$override_set" == "false" ]; then
        echo "Assembling L0 according to default behavior"
        sbt dagL0/assembly
      fi
    else
      echo "Found existing assemblies, and skip assembly was set to true"
    fi
  fi
fi

show_time "SBT Assembly"

mkdir -p ./docker/jars/

for module in "dag-l0" "dag-l1" "keytool" "wallet"
do
  path=$(ls -1t modules/${module}/target/scala-2.13/tessellation-${module}-assembly*.jar | head -n1)
  cp $path ./nodes/${module}.jar
  cp $path ./docker/jars/${module}.jar
done

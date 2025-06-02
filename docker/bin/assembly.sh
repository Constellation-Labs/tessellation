
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

mv ./docker/jars/dag-l0.jar ./docker/jars/gl0.jar
mv ./docker/jars/dag-l1.jar ./docker/jars/gl1.jar

touch ./docker/jars/ml0.jar
touch ./docker/jars/ml1.jar
touch ./docker/jars/dl1.jar


if [ -n "$PUBLISH" ]; then
  sbt publishLocal
fi




assemble_all_metagraph() {
  sbt currencyL0/assembly currencyL1/assembly dataL1/assembly
}


show_time "Starting metagraph assembly"

if [ -z "$METAGRAPH" ]; then
  touch ./docker/jars/ml0.jar
  touch ./docker/jars/ml1.jar
  touch ./docker/jars/dl1.jar
fi

move_metagraph_jar() {
  local module=$1
  local destination=$2
  path=$(ls -1t modules/${module}/target/scala-2.13/*-assembly*.jar | head -n1)
  cp $path ./nodes/${destination}.jar
  cp $path ./docker/jars/${destination}.jar
}


if [ -n "$METAGRAPH" ]; then
  echo "Assembling $METAGRAPH"
  cd $METAGRAPH

  missing=false

  for module in $METAGRAPH_ML0_RELATIVE_PATH $METAGRAPH_ML1_RELATIVE_PATH $METAGRAPH_DL1_RELATIVE_PATH; do
    jar_path=$(ls -1t modules/"$module"/target/scala-2.13/*-assembly*.jar 2>/dev/null | head -n1)
    if [ -z "$jar_path" ]; then
      echo "⚠️  Missing JAR for module: $module"
      missing=true
      break
    fi
  done

  if [ "$missing" = true ]; then
    echo "▶️  One or more modules is missing. Cannot skip assembly. Running full assembly"
    assemble_all_metagraph
  else
    if [ "$SKIP_METAGRAPH_ASSEMBLY" == "false" ]; then
      override_set=false
      if [ "$METAGRAPH_ML0" == "true" ]; then
        echo "Assembling L0"
        sbt currencyL0/assembly
        override_set=true
      fi
      if [ "$METAGRAPH_ML1" == "true" ]; then
        echo "Assembling L1"
        sbt currencyL1/assembly
        override_set=true
      fi
      if [ "$METAGRAPH_DL1" == "true" ]; then
        echo "Assembling L1"
        sbt dataL1/assembly
        override_set=true
      fi
      if [ "$override_set" == "false" ]; then
        echo "Assembling ML0 according to default behavior"
        sbt currencyL0/assembly
      fi
    else
      echo "Found existing assemblies, and skip assembly was set to true"
    fi

  fi

  show_time "SBT Metagraph Assembly"

  mkdir -p ./docker/jars/

  move_metagraph_jar $METAGRAPH_ML0_RELATIVE_PATH "ml0"
  move_metagraph_jar $METAGRAPH_ML1_RELATIVE_PATH "ml1"
  move_metagraph_jar $METAGRAPH_DL1_RELATIVE_PATH "dl1"

fi

assemble_all() {
  sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly tools/assembly
}

# Fast path: skip all assembly when docker/jars/ is pre-populated (e.g., from CI artifact cache)
if [ "$SKIP_ASSEMBLY" = "true" ] && [ -f "$PROJECT_ROOT/docker/jars/gl0.jar" ] && [ -s "$PROJECT_ROOT/docker/jars/gl0.jar" ]; then
  if [ -z "$METAGRAPH" ] || { [ -f "$PROJECT_ROOT/docker/jars/ml0.jar" ] && [ -s "$PROJECT_ROOT/docker/jars/ml0.jar" ]; }; then
    echo "Pre-built JARs found in docker/jars/, skipping assembly"
    if [ -z "$METAGRAPH" ]; then
      touch "$PROJECT_ROOT/docker/jars/ml0.jar"
      touch "$PROJECT_ROOT/docker/jars/cl1.jar"
      touch "$PROJECT_ROOT/docker/jars/dl1.jar"
    fi
    show_time "Assembly (skipped - using pre-built JARs)"
    cd $PROJECT_ROOT
    return 0 2>/dev/null || true
  fi
fi

show_time "Starting assembly"

# If HYPERGRAPH_RELEASE is set, download pre-built JARs instead of building from source
if [ -n "$HYPERGRAPH_RELEASE" ]; then
  echo "Using pre-built hypergraph JARs from release: ${HYPERGRAPH_RELEASE}"
  rm -rf ./docker/jars/ > /dev/null 2>&1 || true
  mkdir -p ./docker/jars/
  
  ./docker/bin/download-release-jars.sh "$HYPERGRAPH_RELEASE" ./docker/jars/
  
  show_time "Downloaded release JARs"
  
  # Skip the rest of hypergraph assembly, jump to metagraph handling
  SKIP_HYPERGRAPH_BUILD=true
else
  SKIP_HYPERGRAPH_BUILD=false
fi

if [ "$SKIP_HYPERGRAPH_BUILD" != "true" ]; then
  # Build hypergraph JARs from source
  if [[ "$INCLUDE_L0" == "false" && "$INCLUDE_L1" == "false" && "$SKIP_ASSEMBLY" == "false" ]]; then
    assemble_all
  else
    missing=false

    for module in dag-l0 dag-l1 keytool wallet tools; do
      set +e
      jar_path=$(ls -1t modules/"$module"/target/scala-2.13/tessellation-"$module"-assembly*.jar 2>/dev/null | head -n1)
      set -e
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

  rm -rf ./docker/jars/ > /dev/null 2>&1 || true;
  mkdir -p ./docker/jars/

  for module in "dag-l0" "dag-l1" "keytool" "wallet" "tools"
  do
    path=$(ls -1t modules/${module}/target/scala-2.13/tessellation-${module}-assembly*.jar | head -n1)
    dest="$PROJECT_ROOT/docker/jars/${module}.jar"
    cp "$path" "$dest"
  done

  mv ./docker/jars/dag-l0.jar ./docker/jars/gl0.jar
  mv ./docker/jars/dag-l1.jar ./docker/jars/gl1.jar
fi


if [ "$PUBLISH" == "true" ]; then
  if [ -n "$HYPERGRAPH_RELEASE" ]; then
    echo "Skipping sdk/publishLocal - using release ${HYPERGRAPH_RELEASE}"
    echo "  Note: Metagraph builds require tessellation-sdk ${HYPERGRAPH_RELEASE#v} from Maven Central."
    echo "  SNAPSHOT versions are not published - use a tagged release."
  else
    echo "Publishing local with version: $TESSELLATION_VERSION"
    sbt --error "set ThisBuild / version := \"$TESSELLATION_VERSION\"" sdk/publishLocal
  fi
fi


assemble_all_metagraph() {
  sbt --error currencyL0/assembly currencyL1/assembly dataL1/assembly
}



if [ -z "$METAGRAPH" ]; then
  touch ./docker/jars/ml0.jar
  touch ./docker/jars/cl1.jar
  touch ./docker/jars/dl1.jar
fi

move_metagraph_jar() {
  local module=$1
  local destination=$2
  path=$(ls -1t modules/${module}/target/scala-2.13/*-assembly*.jar | head -n1)
  dest="$PROJECT_ROOT/docker/jars/${destination}.jar"
  cp "$path" "$dest"
}


if [ -n "$METAGRAPH" ]; then
  echo "Assembling $METAGRAPH"
  cd $METAGRAPH

  missing=false

  for module in $METAGRAPH_ML0_RELATIVE_PATH $METAGRAPH_CL1_RELATIVE_PATH $METAGRAPH_DL1_RELATIVE_PATH; do
    set +e
    jar_path=$(ls -1t modules/"$module"/target/scala-2.13/*-assembly*.jar 2>/dev/null | head -n1)
    set -e
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
        echo "Assembling currencyL0 with explicit version: $TESSELLATION_VERSION"
        sbt --error currencyL0/assembly
        override_set=true
      fi
      if [ "$METAGRAPH_CL1" == "true" ]; then
        echo "Assembling CL1"
        echo "Assembling currencyL1 with explicit version: $TESSELLATION_VERSION"
        sbt --error currencyL1/assembly
        override_set=true
      fi
      if [ "$METAGRAPH_DL1" == "true" ]; then
        echo "Assembling DL1"
        echo "Assembling dataL1 with explicit version: $TESSELLATION_VERSION"
        sbt --error dataL1/assembly
        override_set=true
      fi
      if [ "$override_set" == "false" ]; then
        echo "Assembling ML0 according to default behavior"
        echo "Assembling currencyL0 with explicit version: $TESSELLATION_VERSION"
        sbt --error currencyL0/assembly
      fi
    else
      echo "Found existing assemblies, and skip assembly was set to true"
    fi

  fi

  show_time "SBT Metagraph Assembly"


  move_metagraph_jar $METAGRAPH_ML0_RELATIVE_PATH "ml0"
  move_metagraph_jar $METAGRAPH_CL1_RELATIVE_PATH "cl1"
  move_metagraph_jar $METAGRAPH_DL1_RELATIVE_PATH "dl1"

fi

cd $PROJECT_ROOT
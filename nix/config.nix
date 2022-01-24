{ jdk }:

{
  packageOverides = p: rec {
    java = p.${jdk};

    sbt = p.sbt.override {
      jre = p.${jdk};
    };
  };
}

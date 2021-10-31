#!/bin/sh

if [ -z "${PROG_HOME}" ] ; then
  PRG="$0"

  while [ -h "${PRG}" ] ; do
    ls=$(ls -ld "${PRG}")
    link=$(expr "${ls}" : '.*-> \(.*\)$')
    if expr "${link}" : '/.*' > /dev/null ; then
      PRG="${link}"
    else
      PRG="$(dirname "$PRG")/${link}"
    fi
  done

  workdir=$(pwd)
  PROG_HOME=$(dirname "${PRG}")/..
  PROG_HOME=$(cd "${PROG_HOME}" && pwd)
  cd ${workdir}
fi

for arg do
  shift
  case $arg in
    -D*) JAVA_OPTS="${JAVA_OPTS} ${arg}" ;;
      *) set -- "$@" "${arg}" ;;
  esac
done

################################################################################

JAVA_OPTS="${JAVA_OPTS} -XX:+HeapDumpOnOutOfMemoryError"
JAVA_OPTS="${JAVA_OPTS} -XX:+UseGCOverheadLimit"

################################################################################

JAVA_OPTS="${JAVA_OPTS## }"
JAVA_OPTS="${JAVA_OPTS%% }"

eval exec \
  java \
  "${JAVA_OPTS}" \
  -cp "'${PROG_HOME}/lib/*'" \
  com.openbank.dwh.Main

exit $?
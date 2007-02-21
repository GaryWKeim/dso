#!/bin/sh

#
#  All content copyright (c) 2003-2006 Terracotta, Inc.,
#  except as may otherwise be noted in a separate copyright notice.
#  All rights reserved.
#

TOPDIR=`dirname "$0"`/../../..
. "${TOPDIR}"/bin/tc-functions.sh

TC_CONFIG_PATH="tc-config.xml"
. "${TOPDIR}"/bin/dso-env.sh

tc_java ${TC_JAVA_OPTS} -Dcom.sun.management.jmxremote -cp "classes:lib/org.mortbay.jetty-4.2.20.jar:lib/javax.servlet.jar" demo.sharedqueue.Main "$@"

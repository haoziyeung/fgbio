#!/bin/bash

##################################################################################
# The MIT License                                                                #
#                                                                                #
# Copyright (c) 2015-2016 Fulcrum Genomics LLC                                   #
#                                                                                #
# Permission is hereby granted, free of charge, to any person obtaining a copy   #
# of this software and associated documentation files (the "Software"), to deal  #
# in the Software without restriction, including without limitation the rights   #
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell      #
# copies of the Software, and to permit persons to whom the Software is          #
# furnished to do so, subject to the following conditions:                       #
#                                                                                #
# The above copyright notice and this permission notice shall be included in     #
# all copies or substantial portions of the Software.                            #
#                                                                                #
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR     #
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,       #
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE    #
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER         #
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,  #
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN      #
# THE SOFTWARE.                                                                  #
##################################################################################

###############################################################################
# Simple shell script to generate metrics documentation with scaladoc
###############################################################################

# Uncomment the following line to turn on debugging when running scaladoc
# JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=7777"

sources=$(find src/main/scala -name \*.scala)
cp=$(sbt -Dsbt.log.noformat=true "export runtime:fullClasspath" 2> /dev/null | fgrep -v '[info]')

scaladoc -toolcp $cp -d target -doc-generator com.fulcrumgenomics.internal.FgMetricsDoclet $sources

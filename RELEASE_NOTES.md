# Release Notes

- 2.3.2 (06.11.2024, https://github.com/LittleProxy/LittleProxy/milestone/38?closed=1)
  - Expose proxy to server ctx to access client address  --  thanks to Teodora Kostova (#520)
  - Bump Netty from 4.1.113.Final to 4.1.114.Final
  - Bump Selenium from 4.25.0 to 4.26.0

- 2.3.1 (30.09.2024, https://github.com/LittleProxy/LittleProxy/milestone/37?closed=1)
  - Bump org.seleniumhq.selenium:selenium-java from 4.24.0 to 4.25.0 (#492)
  - Bump dnsjava:dnsjava from 3.6.1 to 3.6.2 (#491)
  - Bump org.apache.logging.log4j:log4j-core from 2.23.1 to 2.24.1 (#489) (#497)

- 2.3.0 (06.09.2024, https://github.com/LittleProxy/LittleProxy/milestone/36?closed=1)
  - #487 remove UDP protocol support (#488)
  - Bump Netty from 4.1.112.Final to 4.1.113.Final (#486)

- 2.2.4 (04.09.2024, https://github.com/LittleProxy/LittleProxy/milestone/35?closed=1)
  - Bump Selenium from 4.22.0 to 4.24.0
  - Bump Netty from 4.1.111.Final to 4.1.112.Final
  - Bump dnsjava from 3.5.3 to 3.6.1

- 2.2.3 (21.06.2024, https://github.com/LittleProxy/LittleProxy/milestone/34?closed=1)
  - Bump selenium from 4.21.0 to 4.22.0 (#433)
  - #37 fix ClassCastException: "PooledUnsafeDirectByteBuf cannot be cast to HttpObject" (#434)

- 2.2.2 (12.06.2024, https://github.com/LittleProxy/LittleProxy/milestone/33?closed=1)
  - Bump selenium from 4.20.0 to 4.21.0
  - Bump jackson from 2.17.0 to 2.17.1
  - Bump netty from 4.1.109.Final to 4.1.111.Final

- 2.2.1 (25.04.2024, https://github.com/LittleProxy/LittleProxy/milestone/32?closed=1)
  - Bump Netty from 4.1.107.Final to 4.1.109.Final
  - Bump Selenium from 4.18.1 to 4.20.0
  - Bump Jackson from 2.16.1 to 2.17.0
  - Bump Slf4j from 2.0.12 to 2.0.13
  - Bump Log4j from 2.23.0 to 2.23.1
  - Bump Guava from 33.0.0-jre to 33.1.0-jre

- 2.2.0 (22.02.2024, https://github.com/LittleProxy/LittleProxy/milestone/31?closed=1)
  - Move the project from groupId "xyz.rogfam" to "io.github.littleproxy"
  - Migrate from JUnit4/Hamcrest to JUnit5/AssertJ (#373)
  - Bump Netty from 4.1.106.Final to 4.1.107.Final (#376)
  - Bump Selenium from 4.17.0 to 4.18.1 (#378) (#379)
  - Bump log4j from 2.22.1 to 2.23.0 (#381)

- 2.1.2 (07.02.2024, https://github.com/LittleProxy/LittleProxy/milestone/30?closed=1)
  - Refactoring & code cleanup & setup IDEA inspections (#370) (#371)
  - Bump Netty from 4.1.103.Final to 4.1.106.Final
  - Bump slf4j from 2.0.9 to 2.0.12
  - Bump log4j from 2.22.0 to 2.22.1 (#357)
  - Bump Selenium from 4.16.1 to 4.17.0 (#367)
  - Bump Guava from 32.1.3-jre to 33.0.0-jre

- 2.1.1 (15.12.2023, https://github.com/LittleProxy/LittleProxy/milestone/29?closed=1)
  - Bump Netty from 4.1.101.Final to 4.1.103.Final   #345 #346
  - Bump selenium from 4.15.0 to 4.16.1   #343 #344
  - Bump log4j from 2.21.1 to 2.22.0  #336
  - Bump commons-lang3 from 3.13.0 to 3.14.0   #338

- 2.1.0 (20.11.2023, https://github.com/LittleProxy/LittleProxy/milestone/28?closed=1)
  - Upgrade from Java 8 to Java 11+
  - Bump Selenium from 4.13.0 to 4.15.0
  - Bump Netty from 4.1.99.Final to 4.1.101.Final
  - Bump Jackson from 2.15.2 to 2.16.0
  - Bump Log4j from 2.20.0 to 2.21.1
  - Bump Guava from 32.1.2-jre to 32.1.3-jre
 
- 2.0.22 (08.10.2023, https://github.com/LittleProxy/LittleProxy/milestone/27?closed=1)
  - #35 Fix Websocket race condition while protocol switching  --  thanks to Craig Andrews for PR #308
  - #307 bump Netty from 4.1.98.Final to 4.1.99.Final

- 2.0.21 (27.09.2023, https://github.com/LittleProxy/LittleProxy/milestone/26?closed=1)
  - #301 Always use a new connection for websockets  --  thanks to Craig Andrews
  - #299 fix problem with filtering proxy Authorization header  --  thanks to Matthias Kraaz for PR #304
  - #297 #306 Bump org.seleniumhq.selenium:selenium-java from 4.12.0 to 4.13.0
  - #303 Bump `netty.version` from 4.1.97.Final to 4.1.98.Final.

- 2.0.20 (04.09.2023, https://github.com/LittleProxy/LittleProxy/milestone/25?closed=1)
  - #295 #131 fix memory leak "LEAK: ByteBuf.release() was not called..."  --  thanks to Sujit Joshi for the fix
  - #284 #291 Bump netty.version from 4.1.95.Final to 4.1.97.Final
  - #286 #293 Bump org.seleniumhq.selenium:selenium-java from 4.10.0 to 4.12.0
  - #285 Bump org.apache.commons:commons-lang3 from 3.12.0 to 3.13.0
  - #287 Bump com.google.guava:guava from 32.1.1-jre to 32.1.2-jre
  - #294 Bump slf4j.version from 2.0.7 to 2.0.9 

- 2.0.19 (22.07.2023, https://github.com/LittleProxy/LittleProxy/milestone/24?closed=1)
  - #283 fix memory leak: On proxy connection unregister, unregister downstream channels - thanks to Craig Andrews
  - #274 Bump Selenium from 4.9.1 to 4.10.0 (see https://github.com/SeleniumHQ/selenium)
  - #266 Bump Jackson from 2.15.1 to 2.15.2
  - #281 Bump guava from 32.0.0-jre to 32.1.1-jre
  - #282 Bump Netty from 4.1.93.Final to 4.1.95.Final
  - #264 Migrate Jetty 9 to Jetty 11 - thanks to Valery Yatsynovich

- 2.0.18 (29.05.2023, https://github.com/LittleProxy/LittleProxy/milestone/23?closed=1)
  - Bump Selenium from 4.8.3 to 4.9.1 (see https://github.com/SeleniumHQ/selenium)
  - #242 Bump Netty from 4.1.90.Final to 4.1.93.Final
  - Bump Jackson from 2.14.2 to 2.15.1
  - Bump guava from 31.1-jre to 32.0.0-jre

- 2.0.17 (01.04.2023, https://github.com/LittleProxy/LittleProxy/milestone/22?closed=1)
  - #235 Bump netty.version from 4.1.89.Final to 4.1.90.Final
  - bump Jackson from 2.13.4 to latest 2.14.2 (fixes several CVEs)
  - #236 Bump slf4j.version from 2.0.6 to 2.0.7
  - #241 Bump selenium-java from 4.8.1 to 4.8.3

- 2.0.16 (27.02.2023, https://github.com/LittleProxy/LittleProxy/milestone/21?closed=1)
  - rename "master" branch to "main"
  - #207 Remove redundant file generated by unit test  --  thanks to Valery Yatsynovich
  - #206 Export certificate to generated by SelfSignedMitmManager KeyStore directory  --  thanks to Valery Yatsynovich
  - Bump slf4j.version from 2.0.5 to 2.0.6
  - Bump log4j-core from 2.19.0 to 2.20.0
  - Bump selenium-java from 4.7.1 to 4.8.1
  - Bump netty.version from 4.1.86.Final to 4.1.89.Final

- 2.0.15 (14.12.2022, https://github.com/LittleProxy/LittleProxy/milestone/20?closed=1)
  - Bump netty-codec-haproxy from 4.1.85.Final to 4.1.86.Final
  - Bump selenium-java from 4.6.0 to 4.7.1
  - Bump slf4j.version from 2.0.4 to 2.0.5
  - Bump httpclient from 4.5.13 to 4.5.14

- 2.0.14 (21.11.2022, https://github.com/LittleProxy/LittleProxy/milestone/19?closed=1)
  - #184 Respectful KeyStore file path while generating certs by `SelfSignedMitmManager`  --  thanks to Valery Yatsynovich
  - #187 CI: run build on all major OS-s  --  thanks to Valery Yatsynovich
  - #183 Bump netty from 4.1.82.Final to 4.1.85.Final  --  thanks to Valery Yatsynovich for fixing tests after upgrading Netty.
  - #189 Bump slf4j.version from 2.0.3 to 2.0.4
  - Bump jackson-databind from 2.13.2.2 to 2.13.4
  - #191 Bump dnsjava from 3.5.1 to 3.5.2

- 2.0.13 (04.10.2022)
  - #170 restore transitive dependencies in generated pom  --  thanks to Mateusz Pietryga for PR #171
  - Bump slf4j from 2.0.1 to 2.0.3
  - Bump selenium-java from 4.4.0 to 4.5.0

- 2.0.12 (23.09.2022)
  - #145 Restore Keep-Alive value when filtering short-circuit response  --  thanks to krlvm for PR
  - Bump netty from 4.1.79.Final to 4.1.82.Final
  - Bump slf4j from 1.7.36 to 2.0.1
  - Bump log4j-core from 2.18.0 to 2.19.0

- 2.0.11 (13.08.2022)
  - #131 fix memory leak: release byte buffer when closing request - see PR #141
  - #142 fix some "modify response" problem, see https://github.com/adamfisk/LittleProxy/issues/359
  - #144 HTTP CONNECT can't be Keep-Alive - thanks Michel Belleau for PR #144

- 2.0.10 (20.07.2022)
  - #135 Bump netty.version from 4.1.77.Final to 4.1.79.Final
  - #132 Bump selenium-java from 4.1.4 to 4.3.0
  - #118 Bump dnsjava from 3.5.0 to 3.5.1

- 2.0.9 (10.05.2022)
  - #115 reverted to maven-shade-plugin 3.2.4 (because 3.3.0 generated artifact without compile/runtime dependencies)

- 2.0.8 (06.05.2022)
  - #26 fixed TLS 1.3 handshake bug  --  thanks Dan Powell for PR https://github.com/LittleProxy/LittleProxy/pull/26
  - Bumped log4j-core from 2.17.0 to 2.17.2
  - Bumped netty from 4.1.71 to 4.1.76
  - Bumped slf4j from 1.7.30 to 1.7.36
  - Bumped jackson from 2.11.3 to 2.12.6.1
  - Bumped guava from 30.1-jre to 31.1-jre
  - Bumped commons-cli from 1.4 to 1.5.0
  - Relocated slf4j-log4j to slf4j-reload4j
  - moved the project to https://github.com/LittleProxy/LittleProxy
  - moved CI from Travis to https://github.com/LittleProxy/LittleProxy/actions

- 2.0.7 (21.12.2021)
  - Bumped log4j-core from 2.16.0 to 2.17.0

- 2.0.6
  - Use single Hamcrest dependency in tests
  - Improve logging performance
  - Bumped netty-codec from 4.1.63.Final to 4.1.68.Final
  - Bump netty-codec-http from 4.1.68.Final to 4.1.71.Final
  - Bumped log4j-core from 2.14.0 to 2.16.0
  - Added public key file

- 2.0.5
  - Bumped jetty-server from 9.4.34.v20201102 to 9.4.41.v20210516.

- 2.0.4
  - Android compatibility fix (PR #76)
  - Fix NoSuchElementException when switching protocols to WebSocket (PR #78)
  - Prevent NullPointerException in ProxyUtils::isHEAD (PR #79)
  - Fixes in ThrottlingTest, Upgrade to Netty 4.1.63.Final (PR #65)
  - Fix NPEs in getReadThrottle and getWriteThrottle when globalTrafficShapingHandler is null (PR #80)

- 2.0.3
  - Upgrade guava to 30.1
  - Threads are now set as daemon (not user, which is the default) threads so the JVM exits as expected when all other threads stop.
  - Close thread pool if proxy fails to start

- 2.0.2
  - Support for WebSockets with MITM in transparent mode
  - Support for per request conditional MITM

- 2.0.1
  - Removed beta tag from version
  - Updated various dependency versions
  - Re-ordered the release notes so the newest stuff is at the top

- 2.0.0-beta-6
  - Cleaned up old code to conform with newer version of Netty
  - Deprecated UDT support because it's deprecated in Netty
  - Removed performance test code because it seems to be confusing GitHub into thinking that this is a PHP project

- 2.0.0-beta-5
  - Treat an upstream SOCKS proxy as if it is the origin server
  - Fixed memoryLeak in ClientToProxyConnection

- 2.0.0-beta-4
  - Allow users to set their own server group within the bootstrap helper
  - Added support for chained SOCKS proxies

- 2.0.0-beta-3
  - Upgraded Netty, guava, Hamcrest, Jetty, Selenium, Apache commons cli and lang3
  - Upgrade Maven plugins to the latest versions

- 2.0.0-beta-2
  - Added support for proxy protocol.  See https://www.haproxy.com/blog/haproxy/proxy-protocol/ and https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt for protocol details.

- 2.0.0-beta-1
  - New Maven coordinates
  - Moved from Java 7 to 8
  - Updated dependency versions
  - **Breaking change:**  Made client details available to ChainedProxyManager
  - Refactored MITM manager to accept engine with user-defined parameters
  - Added ability to load keystore from classpath

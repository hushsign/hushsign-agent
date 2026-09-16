//SMSLib - A Java API library for sending and receiving SMS via a GSM modem
//or other supported gateways.
//
//Copyright (C) 2002-2010, Thanasis Delenikas, Athens/GREECE.
//
//SMSLib is distributed under the terms of the Apache License version 2.0
//
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
package org.smslib.helper;

/**
 * TRIMMED vendored file (see VENDORING.md): only the two helpers used by the
 * vendored AT layer remain. Everything else (network, date, string helpers)
 * was pruned with the Service/queue machinery.
 */
public class Common
{
        public static void countSheeps(int n)
        {
                try
                {
                        Thread.sleep(n);
                }
                catch (InterruptedException e)
                {
                        Thread.currentThread().interrupt();
                }
        }

        public static boolean isNullOrEmpty(String s)
        {
                return (s == null) || (s.length() == 0);
        }
}

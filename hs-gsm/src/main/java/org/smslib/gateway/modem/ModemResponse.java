// SMSLib - A Java API library for sending and receiving SMS via a GSM modem
// or other supported gateways.
// Copyright (C) 2002-2010, Thanasis Delenikas, Athens/GREECE.
// SMSLib is distributed under the terms of the Apache License Version 2.0
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Modified by HushSign (P1-S1..P1-S14): the engine/queue/settings machinery is
// pruned and the AT layer is bound to com.hushsign.agent.gsm.modem.ModemContext.
// See hs-gsm/VENDORING.md for the complete edit log.

package org.smslib.gateway.modem;

public class ModemResponse
{
	String responseData;

	boolean responseOk;

	public ModemResponse(String responseData, boolean responseOk)
	{
		this.responseData = responseData;
		this.responseOk = responseOk;
	}

	public String getResponseData()
	{
		return this.responseData;
	}

	public boolean isResponseOk()
	{
		return this.responseOk;
	}
}

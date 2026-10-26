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

import java.util.Collection;
import java.util.HashSet;

public class DeviceInformation
{
	public enum Modes
	{
		PDU, TEXT
	}

	String manufacturer = "N/A";

	String model = "N/A";

	String swVersion = "N/A";

	String serialNo = "N/A";

	String imsi = "N/A";

	int rssi = 0;

	Modes mode;

	Collection<String> supportedEncodings = new HashSet<>();

	String encoding;

	public String getManufacturer()
	{
		return this.manufacturer;
	}

	public void setManufacturer(String manufacturer)
	{
		this.manufacturer = manufacturer;
	}

	public String getModel()
	{
		return this.model;
	}

	public void setModel(String model)
	{
		this.model = model;
	}

	public String getSwVersion()
	{
		return this.swVersion;
	}

	public void setSwVersion(String swVersion)
	{
		this.swVersion = swVersion;
	}

	public String getSerialNo()
	{
		return this.serialNo;
	}

	public void setSerialNo(String serialNo)
	{
		this.serialNo = serialNo;
	}

	public String getImsi()
	{
		return this.imsi;
	}

	public void setImsi(String imsi)
	{
		this.imsi = imsi;
	}

	public int getRssi()
	{
		return this.rssi;
	}

	public void setRssi(int rssi)
	{
		this.rssi = rssi;
	}

	public Modes getMode()
	{
		return this.mode;
	}

	public void setMode(Modes mode)
	{
		this.mode = mode;
	}

	public Collection<String> getSupportedEncodings()
	{
		return this.supportedEncodings;
	}

	public String getEncoding()
	{
		return this.encoding;
	}

	public void setEncoding(String encoding)
	{
		this.encoding = encoding;
	}

	@Override
	public String toString()
	{
		return String.format("MANUF:%s, MODEL:%s, SERNO:%s, IMSI:%s, SW:%s, RSSI:%ddBm, MODE:%s", getManufacturer(), getModel(), getSerialNo(), getImsi(), getSwVersion(), getRssi(), getMode());
	}
}

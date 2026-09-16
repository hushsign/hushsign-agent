
package org.smslib.core;

import java.util.BitSet;

public class Capabilities
{
	BitSet caps = new BitSet();

	public enum Caps
	{
		CanSendMessage, CanSendBinaryMessage, CanSendUnicodeMessage, CanSendWapMessage, CanSendFlashMessage, CanSendPortInfo, CanSetSenderId, CanSplitMessages, CanRequestDeliveryStatus, CanQueryDeliveryStatus, CanQueryCreditBalance, CanQueryCoverage, CanSetValidityPeriod
	}

	public Capabilities()
	{
	}

	public void set(Caps c)
	{
		this.caps.set(c.ordinal());
	}

	public void clear(Caps c)
	{
		this.caps.clear(c.ordinal());
	}

	public BitSet getCapabilities()
	{
		return (BitSet) this.caps.clone();
	}

	// HushSign: matches(OutboundMessage) removed with the message/routing model.

	public boolean supports(Caps c)
	{
		BitSet bs = new BitSet();
		bs.set(c.ordinal());
		BitSet originalBs = (BitSet) getCapabilities().clone();
		originalBs.and(bs);
		return (originalBs.cardinality() == bs.cardinality());
	}

	@Override
	public String toString()
	{
		BitSet bs = (BitSet) getCapabilities().clone();
		StringBuffer b = new StringBuffer();
		for (Caps c : Caps.values())
		{
			b.append(String.format("%-30s : ", c.toString()));
			b.append(bs.get(c.ordinal()) ? "YES" : "NO");
			b.append("\n");
		}
		return b.toString();
	}
}

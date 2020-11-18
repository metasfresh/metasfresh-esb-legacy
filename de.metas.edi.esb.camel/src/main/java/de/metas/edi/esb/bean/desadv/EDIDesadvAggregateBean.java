package de.metas.edi.esb.bean.desadv;

/*
 * #%L
 * de.metas.edi.esb
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import static de.metas.edi.esb.commons.Util.formatNumber;
import static de.metas.edi.esb.commons.Util.toDate;
import static de.metas.edi.esb.commons.ValidationHelper.validateObject;
import static de.metas.edi.esb.commons.ValidationHelper.validateString;
import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.RuntimeCamelException;
import org.milyn.payload.JavaSource;

import de.metas.edi.esb.commons.Constants;
import de.metas.edi.esb.commons.SystemTime;
import de.metas.edi.esb.commons.Util;
import de.metas.edi.esb.jaxb.EDIExpCBPartnerLocationType;
import de.metas.edi.esb.jaxb.EDIExpCBPartnerType;
import de.metas.edi.esb.jaxb.EDIExpDesadvLinePackType;
import de.metas.edi.esb.jaxb.EDIExpDesadvLineType;
import de.metas.edi.esb.jaxb.EDIExpDesadvType;
import de.metas.edi.esb.pojo.desadv.compudata.H000;
import de.metas.edi.esb.pojo.desadv.compudata.H100;
import de.metas.edi.esb.pojo.desadv.compudata.P050;
import de.metas.edi.esb.pojo.desadv.compudata.P060;
import de.metas.edi.esb.pojo.desadv.compudata.P100;
import de.metas.edi.esb.pojo.desadv.compudata.P102;
import de.metas.edi.esb.pojo.desadv.compudata.join.JP060P100;
import de.metas.edi.esb.route.exports.EDIDesadvRoute;
import lombok.NonNull;

public class EDIDesadvAggregateBean extends AbstractEDIDesadvCommonBean
{
	/**
	 * <ul>
	 * <li>IN: {@link EDIExpDesadvType}</li>
	 * <li>OUT: {@link H000}</li>
	 * </ul>
	 *
	 * @param exchange
	 */
	@Override
	public final void createEDIData(final Exchange exchange)
	{
		final EDIExpDesadvType xmlDesadv = validateExchange(exchange); // throw exceptions if mandatory fields are missing
		sortLines(xmlDesadv);

		final DecimalFormat decimalFormat = exchange.getProperty(Constants.DECIMAL_FORMAT, DecimalFormat.class);

		final H000 desadvDocument = createEDIDesadvFromXMLBean(xmlDesadv, decimalFormat, exchange.getProperty(EDIDesadvRoute.EDI_DESADV_IS_TEST, String.class));

		final JavaSource source = new JavaSource(desadvDocument);
		exchange.getIn().setBody(source, H000.class);
	}

	private void sortLines(final EDIExpDesadvType desadv)
	{
		// sort M_InOutLines
		final List<EDIExpDesadvLineType> desadvLines = desadv.getEDIExpDesadvLine();
		Collections.sort(desadvLines, new Comparator<EDIExpDesadvLineType>()
		{
			@Override
			public int compare(final EDIExpDesadvLineType iol1, final EDIExpDesadvLineType iol2)
			{
				final BigInteger line1 = iol1.getLine();
				final BigInteger line2 = iol2.getLine();
				return line1.compareTo(line2);
			}
		});
	}

	private H000 createEDIDesadvFromXMLBean(final EDIExpDesadvType xmlDesadv, final DecimalFormat decimalFormat, final String testFlag)
	{
		final H000 h000 = new H000();
		h000.setMessageDate(SystemTime.asDate());
		h000.setReceiver(xmlDesadv.getCBPartnerID().getEdiRecipientGLN());

		h000.setReference(formatNumber(xmlDesadv.getSequenceNoAttr(), decimalFormat));
		h000.setTestFlag(testFlag);

		final List<H100> h100Lines = new ArrayList<H100>();
		// for (final EDIExpMInOutType xmlInOut : xmlDesadv.getEDIExpMInOut())
		// {
		final List<H100> partialH100Lines = createH100LinesFromXmlDesadv(xmlDesadv, decimalFormat);
		h100Lines.addAll(partialH100Lines);
		// }
		h000.setH100Lines(h100Lines);

		return h000;
	}

	/**
	 * Validate document, and throw {@link RuntimeCamelException} if mandatory fields or properties are missing or empty.
	 *
	 * @param exchange
	 *
	 * @return {@link EDIExpDesadvType} DESADV(aggregated M_InOuts) JAXB object
	 */
	private EDIExpDesadvType validateExchange(final Exchange exchange)
	{
		// validate mandatory exchange properties
		validateString(exchange.getProperty(EDIDesadvRoute.EDI_DESADV_IS_TEST, String.class), "exchange property IsTest cannot be null or empty");

		final EDIExpDesadvType xmlDesadv = exchange.getIn().getBody(EDIExpDesadvType.class);
		validateObject(xmlDesadv, "@EDI.DESADV.XmlNotNull@"); // DESADV body shall not be null

		// validate mandatory types (not null)
		validateObject(xmlDesadv.getCBPartnerID(), "@FillMandatory@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @C_BPartner_ID@");
		validateObject(xmlDesadv.getBillLocationID(), "@FillMandatory@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @Bill_Location_ID@");
		validateObject(xmlDesadv.getSequenceNoAttr(), "@FillMandatory@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @SequenceNoAttr@");
		validateObject(xmlDesadv.getMovementDate(), "@FillMandatory@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @MovementDate@");
		validateObject(xmlDesadv.getDateOrdered(), "@FillMandatory@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @DateOrdered@");
		validateObject(xmlDesadv.getCBPartnerLocationID(), "@FillMandatory@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @C_BPartner_Location_ID@");

		validateObject(xmlDesadv.getCCurrencyID(), "@FillMandatory@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @C_Currency_ID@");

		// validate strings (not null or empty)
		validateString(xmlDesadv.getCBPartnerID().getEdiRecipientGLN(), "@FillMandatory@ @C_BPartner_ID@=" + xmlDesadv.getCBPartnerID().getValue() + " @EdiRecipientGLN@");
		validateString(xmlDesadv.getCBPartnerLocationID().getGLN(), "@FillMandatory@ @C_BPartner_Location_ID@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @GLN@");
		validateObject(xmlDesadv.getBillLocationID().getGLN(), "@FillMandatory@ @Bill_Location_ID@ @EDI_DESADV_ID@=" + xmlDesadv.getDocumentNo() + " @GLN@");

		// Evaluate EDI_DesadvLines
		final List<EDIExpDesadvLineType> ediExpDesadvLines = xmlDesadv.getEDIExpDesadvLine();
		if (ediExpDesadvLines.isEmpty())
		{
			throw new RuntimeCamelException("@EDI.DESADV.ContainsDesadvLines@");
		}

		for (final EDIExpDesadvLineType xmlDesadvLine : ediExpDesadvLines)
		{
			// validate mandatory types (not null)
			validateObject(xmlDesadvLine.getLine(), "@FillMandatory@  @EDI_DesadvLine_ID@ @Line@");

			validateObject(xmlDesadvLine.getQtyDeliveredInUOM(), "@FillMandatory@ @EDI_DesadvLine_ID@=" + xmlDesadvLine.getLine() + " @QtyDeliveredInUOM@");
			validateObject(xmlDesadvLine.getCUOMID(), "@FillMandatory@ @EDI_DesadvLine_ID@=" + xmlDesadvLine.getLine() + " @C_UOM_ID@");
			validateObject(xmlDesadvLine.getMProductID(), "@FillMandatory@ @EDI_DesadvLine_ID@=" + xmlDesadvLine.getLine() + " @M_Product_ID@");

			validateObject(xmlDesadvLine.getQtyEntered(), "@FillMandatory@ @EDI_DesadvLine_ID@=" + xmlDesadvLine.getLine() + " @QtyEntered@");

			validateString(xmlDesadvLine.getProductNo(), "@FillMandatory@ @EDI_DesadvLine_ID@ " + xmlDesadvLine.getLine() + " @ProductNo@");
			validateString(xmlDesadvLine.getEANCU(), "@FillMandatory@ @EDI_DesadvLine_ID@ " + xmlDesadvLine.getLine() + " @EANCU@");
			validateString(xmlDesadvLine.getProductDescription(), "@FillMandatory@ @EDI_DesadvLine_ID@ " + xmlDesadvLine.getLine() + " @ProductDescription@");
		}

		return xmlDesadv;
	}

	protected final List<H100> createH100LinesFromXmlDesadv(final EDIExpDesadvType xmlDesadv, final DecimalFormat decimalFormat)
	{
		final H100 h100 = new H100();

		final String orderBillLocationGLN = xmlDesadv.getBillLocationID().getGLN();
		h100.setBuyerID(orderBillLocationGLN);

		EDIExpCBPartnerLocationType deliveryLocation = xmlDesadv.getHandOverLocationID();
		if (deliveryLocation == null)
		{
			deliveryLocation = xmlDesadv.getCBPartnerLocationID();
		}

		h100.setDeliveryID(deliveryLocation.getGLN());
		h100.setDeliveryName(Util.normalize(deliveryLocation.getName()));

		h100.setDeliveryDate(toDate(xmlDesadv.getMovementDate()));

		h100.setDespatchNoteNumber(xmlDesadv.getDocumentNo());
		h100.setDocumentNo(xmlDesadv.getDocumentNo());
		h100.setInvoiceID(voidString);
		h100.setMessageDate(SystemTime.asDate());
		h100.setMessageNo(formatNumber(xmlDesadv.getSequenceNoAttr(), decimalFormat));

		h100.setOrderDate(toDate(xmlDesadv.getDateOrdered()));
		// 05768
		if (xmlDesadv.getPOReference() != null && !xmlDesadv.getPOReference().isEmpty())
		{
			h100.setOrderNumber(xmlDesadv.getPOReference());
		}
		else
		{
			h100.setOrderNumber(Util.mkOwnOrderNumber(xmlDesadv.getDocumentNo()));
		}

		final EDIExpCBPartnerType partner = xmlDesadv.getCBPartnerID();
		h100.setPartner(partner.getEdiRecipientGLN());

		h100.setProdGrpCode(voidString);
		h100.setRampeID(voidString);
		h100.setReferenceCode(voidString);

		h100.setStoreName(Util.normalize(partner.getName()));
		h100.setStoreNumber(xmlDesadv.getCBPartnerLocationID().getGLN());

		h100.setSupplierNo(voidString);
		h100.setTransportCode(voidString);
		h100.setTransportName(voidString);
		h100.setTransportReference(voidString);

		h100.setP050(createP050FromXmlDesadv(xmlDesadv, decimalFormat));

		// we only have one M_InOut document per EDI file ATM, but we also support more if necessary
		final List<H100> h100Lines = new ArrayList<H100>();
		h100Lines.add(h100);
		return h100Lines;
	}

	private P050 createP050FromXmlDesadv(final EDIExpDesadvType xmlDesadv,
			final DecimalFormat decimalFormat)
	{
		final P050 p050 = new P050();

		p050.setPartner(xmlDesadv.getCBPartnerID().getEdiRecipientGLN());
		p050.setMessageNo(formatNumber(xmlDesadv.getSequenceNoAttr(), decimalFormat));

		final List<P102> p102Lines = new ArrayList<P102>();
		for (final EDIExpDesadvLineType xmlDesadvLine : xmlDesadv.getEDIExpDesadvLine())
		{
			if (xmlDesadvLine.getQtyDeliveredInUOM().signum() == 0)
			{
				p102Lines.add(createP102Line(xmlDesadv, xmlDesadvLine, decimalFormat));
			}
		}
		p050.setP102Lines(p102Lines);

		int cpsCounter = 2; // see wiki

		final List<JP060P100> joinP060P100Lines = new ArrayList<JP060P100>();
		for (final EDIExpDesadvLineType xmlDesadvLine : xmlDesadv.getEDIExpDesadvLine())
		{
			for (final EDIExpDesadvLinePackType pack : xmlDesadvLine.getEDIExpDesadvLinePack())
			{
				final LineAndPack lineAndPack = new LineAndPack(xmlDesadvLine, pack);
				joinP060P100Lines.add(createJoinP060P100Lines(xmlDesadv, lineAndPack, decimalFormat, cpsCounter));
				cpsCounter++;
			}
		}
		p050.setJoinP060P100Lines(joinP060P100Lines);

		final int orderLineCount = xmlDesadv.getEDIExpDesadvLine().size();
		p050.setPackageQty(formatNumber(orderLineCount, decimalFormat));

		p050.setPackageType(voidString);

		return p050;
	}

	private JP060P100 createJoinP060P100Lines(final EDIExpDesadvType xmlDesadv,
			@NonNull final LineAndPack lineAndPack,
			final DecimalFormat decimalFormat,
			final int cpsCounter)
	{
		final JP060P100 join = new JP060P100();

		join.setP060(createP060(xmlDesadv, lineAndPack.getPack(), decimalFormat, cpsCounter));
		join.setP100(createP100(xmlDesadv, lineAndPack, decimalFormat));

		return join;
	}

	private P060 createP060(
			@NonNull final EDIExpDesadvType xmlDesadv,
			final EDIExpDesadvLinePackType pack,
			@NonNull final DecimalFormat decimalFormat,
			final int cpsCounter)
	{
		final P060 p060 = new P060();

		p060.setcPScounter(formatNumber(BigInteger.valueOf(cpsCounter), decimalFormat));
		p060.setInnerOuterCode(voidString);
		p060.setMessageNo(formatNumber(xmlDesadv.getSequenceNoAttr(), decimalFormat));

		// p060.setPalettQTY(xmlInOutLine.getCOrderLineID().getQtyItemCapacity()); // leave empty for now

		final PackagingCode packagingCode = PackagingCode.ofNullableCode(pack.getMHUPackagingCodeLUText());
		if (packagingCode != null)
		{
			final String compudataPackagingCode;
			switch (packagingCode)
			{
				case ISO1:
					compudataPackagingCode = "201";
					break;
				case ISO2:
					compudataPackagingCode = "200";
					break;
				case ONEW:
					compudataPackagingCode = "08";
					break;
				default:
					compudataPackagingCode = "201"; // assume "EUR-palette" by default
			}
			p060.setPalettTyp(compudataPackagingCode);
		}

		p060.setPartner(xmlDesadv.getCBPartnerID().getEdiRecipientGLN());

		final String sscc18Value = Util.removePrecedingZeros(pack == null ? "" : pack.getIPASSCC18());
		p060.setNormalSSCC(sscc18Value);
		p060.setGrainNummer(pack.getGTINLUPackingMaterial());

		// p060.setBruttogewicht(xmlInOutLine.getMProductID().getWeight()); // leave empty for now
		// p060.setVolumen(xmlInOutLine.getMProductID().getVolume()); // leave empty for now

		return p060;
	}

	private P100 createP100(
			final EDIExpDesadvType xmlDesadv,
			@NonNull final LineAndPack lineAndPack,
			final DecimalFormat decimalFormat)
	{
		final P100 p100 = new P100();

		final @NonNull EDIExpDesadvLineType xmlDesadvLine = lineAndPack.getLine();
		final EDIExpDesadvLinePackType pack = lineAndPack.getPack();

		p100.setArtDescription(xmlDesadvLine.getProductDescription() == null ? voidString : xmlDesadvLine.getProductDescription());
		p100.setArticleClass(voidString);
		// p100.setBestBeforeDate(EDIDesadvBean.voidDate); // leave empty
		p100.setChargenNo(voidString);

		p100.setcUperTU(
				formatNumber(pack.getQtyCU(), // might be OK: returning our internal CUperTU-Qty, as we also return or CU-Qtys
						decimalFormat));

		// note that validateExchange() made sure there is at least one
		p100.setCurrency(xmlDesadv.getCCurrencyID().getISOCode());

		p100.setDeliverQTY(formatNumber(
				pack.getQtyCUsPerLU(), // OK internal product/CU-UOM.
				decimalFormat));
		p100.setDeliverUnit(xmlDesadvLine.getCUOMID().getX12DE355());

		// "12" => Liefermenge
		// "11" => Teilmenge
		p100.setDeliverQual("12"); // TODO hardcoded
		BigDecimal qtyDelivered = xmlDesadvLine.getQtyDeliveredInUOM(); // OK internal product/CU-UOM
		if (qtyDelivered == null)
		{
			qtyDelivered = BigDecimal.ZERO;
		}
		p100.setDifferenceQTY(formatNumber(xmlDesadvLine.getQtyEntered().subtract(qtyDelivered), decimalFormat));

		final String discrepancyCode = getDiscrepancyCode(xmlDesadvLine);
		p100.setDiscrepancyCode(discrepancyCode);

		p100.setDiscrepancyText(voidString);
		p100.setDeliveryDate(toDate(xmlDesadv.getMovementDate()));
		p100.setDetailPrice(voidString);
		p100.setUnitCode(voidString);
		// p100.setDiffDeliveryDate(EDIDesadvBean.voidDate);
		p100.setEanTU(xmlDesadvLine.getEANTU());
		p100.setMessageNo(formatNumber(xmlDesadv.getSequenceNoAttr(), decimalFormat));
		// 05768
		if (xmlDesadv.getPOReference() != null && !xmlDesadv.getPOReference().isEmpty())
		{
			p100.setOrderNo(xmlDesadv.getPOReference());
		}
		else
		{
			p100.setOrderNo(Util.mkOwnOrderNumber(xmlDesadv.getDocumentNo()));
		}

		p100.setOrderPosNo(formatNumber(xmlDesadvLine.getLine(), decimalFormat));
		p100.setPartner(xmlDesadv.getCBPartnerID().getEdiRecipientGLN());
		p100.setPositionNo(formatNumber(xmlDesadvLine.getLine(), decimalFormat));
		// p100.setSellBeforeDate(EDIDesadvBean.voidDate);
		// p100.setProductionDate(EDIDesadvBean.voidDate);
		p100.setStoreNumber(voidString);
		p100.setSupplierArtNo(voidString);

		p100.setEanArtNo(xmlDesadvLine.getEANCU());
		p100.setBuyerArtNo(xmlDesadvLine.getProductNo());
		p100.setArtDescription(xmlDesadvLine.getProductDescription() == null ? voidString : xmlDesadvLine.getProductDescription());
		p100.setGrainItemNummer(pack.getGTINTUPackingMaterial());
		return p100;
	}

	/**
	 * @param xmlDesadv
	 * @param nullDeliveryLine
	 * @return P102 line
	 */
	private P102 createP102Line(final EDIExpDesadvType xmlDesadv,
			final EDIExpDesadvLineType xmlDesadvLine,
			final DecimalFormat decimalFormat)
	{
		final P102 p102 = new P102();

		// note that validateExchange() made sure there is at least one
		// final EDIExpMInOutType xmlInOut = xmlDesadv.getEDIExpMInOut().get(0);

		// final EDIExpCOrderLineType orderLine = nullDeliveryLine.getCOrderLineID();
		p102.setArtDescription(xmlDesadvLine.getProductDescription() == null ? voidString : xmlDesadvLine.getProductDescription());
		p102.setArticleClass(voidString);
		// p102.setBestBeforeDate(EDIDesadvBean.voidDate); // leave empty
		p102.setChargenNo(voidString);
		p102.setcUperTU(formatNumber(ZERO, decimalFormat));
		p102.setCurrency(xmlDesadv.getCCurrencyID().getISOCode());

		p102.setDeliverQTY(formatNumber(ZERO, decimalFormat));
		p102.setDeliverUnit(xmlDesadvLine.getCUOMID().getX12DE355());

		p102.setDeliverQual("12"); // TODO hardcoded
		final BigDecimal qtyDelivered = ZERO;

		p102.setDifferenceQTY(formatNumber(xmlDesadvLine.getQtyEntered().subtract(qtyDelivered), decimalFormat));

		final String discrepancyCode = getDiscrepancyCode(xmlDesadvLine);
		p102.setDiscrepancyCode(discrepancyCode); // not used here

		p102.setDeliveryDate(toDate(xmlDesadv.getMovementDate()));
		p102.setDetailPrice(voidString);
		p102.setUnitCode(voidString);
		// p102.setDiffDeliveryDate(EDIDesadvBean.voidDate);
		p102.setEanTU(voidString);
		p102.setMessageNo(formatNumber(xmlDesadv.getSequenceNoAttr(), decimalFormat));
		// 05768
		if (xmlDesadv.getPOReference() != null && !xmlDesadv.getPOReference().isEmpty())
		{
			p102.setOrderNo(xmlDesadv.getPOReference());
		}
		else
		{
			p102.setOrderNo(Util.mkOwnOrderNumber(xmlDesadv.getDocumentNo()));
		}
		p102.setOrderPosNo(formatNumber(xmlDesadvLine.getLine(), decimalFormat));
		p102.setPartner(xmlDesadv.getCBPartnerID().getEdiRecipientGLN());
		p102.setPositionNo(formatNumber(xmlDesadvLine.getLine(), decimalFormat));
		// p102.setSellBeforeDate(EDIDesadvBean.voidDate);
		// p102.setProductionDate(EDIDesadvBean.voidDate);
		p102.setStoreNumber(voidString);
		p102.setSupplierArtNo(voidString);

		p102.setEanArtNo(xmlDesadvLine.getUPC());
		p102.setBuyerArtNo(xmlDesadvLine.getProductNo());
		p102.setArtDescription(xmlDesadvLine.getProductDescription() == null ? voidString : xmlDesadvLine.getProductDescription());

		return p102;
	}

	private String getDiscrepancyCode(final EDIExpDesadvLineType xmlDesadvLine)
	{
		final String discrepancyCode;
		if (Util.isEmpty(xmlDesadvLine.getIsSubsequentDeliveryPlanned()))
		{
			discrepancyCode = voidString; // shouldn't happend
		}
		else if (Boolean.parseBoolean(xmlDesadvLine.getIsSubsequentDeliveryPlanned()))
		{
			discrepancyCode = "BP"; // = Shipment partial - back order to follow
		}
		else
		{
			discrepancyCode = "CP"; // = shipment partial - considered complete, no backorder;
		}
		return discrepancyCode;
	}
}

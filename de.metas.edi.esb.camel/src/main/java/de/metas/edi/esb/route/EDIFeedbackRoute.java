package de.metas.edi.esb.route;

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */


import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.converter.jaxb.JaxbDataFormat;

import de.metas.edi.esb.commons.Constants;
import de.metas.edi.esb.jaxb.EDIDesadvFeedbackType;
import de.metas.edi.esb.jaxb.EDIInvoiceFeedbackType;
import de.metas.edi.esb.processor.feedback.EDIXmlErrorFeedbackProcessor;
import de.metas.edi.esb.processor.feedback.helper.EDIXmlFeedbackHelper;
import de.metas.edi.esb.route.exports.EDIDesadvRoute;
import de.metas.edi.esb.route.exports.EDIInvoiceRoute;

/**
 * In order to avoid endless loops when processing feedback, we're NOT extending AbstractEDIRoute here.<br>
 * Instead, we're using our own exception handler here to catch any exception in feedback, log it, and stop the route from continuing execution.<br>
 *
 * <b>NOTE: Any exception that occurs here should due to misconfiguring the properties.</b>
 *
 * @author al
 */
public class EDIFeedbackRoute extends RouteBuilder
{
	/**
	 * The local folder where the EDI file will be stored if the conversion is <em>not</em> successful
	 */
	private static final String EP_EDI_LOCAL_ERROR = "{{edi.local.error}}";

	private static final String EP_EDI_ERROR_COMMON = "direct:edi.error.common";

	@Override
	public void configure()
	{
		// Catch any exception in feedback, log it, and stop the route from continuing execution.
		onException(Exception.class)
				.handled(true)
				.log(LoggingLevel.ERROR, property(Exchange.EXCEPTION_CAUGHT).toString())
				.to(AbstractEDIRoute.EP_EDI_LOG_ExceptionHandler)
				.stop();

		// At the moment, there's no need to differentiate between the DeadLetterChannel and Exception,
		// so send them both to the same queue to avoid duplicate code
		from(AbstractEDIRoute.EP_EDI_ERROR)
				.to(EDIFeedbackRoute.EP_EDI_ERROR_COMMON);

		from(AbstractEDIRoute.EP_EDI_DEADLETTER)
				.to(EDIFeedbackRoute.EP_EDI_ERROR_COMMON);

		final Processor errorInvoiceProcessor = new EDIXmlErrorFeedbackProcessor<EDIInvoiceFeedbackType>(EDIInvoiceFeedbackType.class,
				EDIInvoiceRoute.EDIInvoiceFeedback_QNAME, EDIInvoiceRoute.METHOD_setCInvoiceID); // FIXME ugly
		final Processor errorDesadvProcessor = new EDIXmlErrorFeedbackProcessor<EDIDesadvFeedbackType>(EDIDesadvFeedbackType.class,
				EDIDesadvRoute.EDIDesadvFeedback_QNAME, EDIDesadvRoute.METHOD_setEDIDesadvID); // FIXME ugly

		// @formatter:off
		from(EDIFeedbackRoute.EP_EDI_ERROR_COMMON)
				.to(AbstractEDIRoute.EP_EDI_LOG_ExceptionHandler)
				.choice()
					.when(property(AbstractEDIRoute.IS_CREATE_XML_FEEDBACK).isEqualTo(true))
						.log(LoggingLevel.INFO, "EDI: Creating error feedback XML Java Object...")
						.choice()
							//.when(body().isInstanceOf(EDICctopInvoicVType.class))
							.when(header(EDIXmlFeedbackHelper.HEADER_ROUTE_ID).isEqualTo(EDIInvoiceRoute.ROUTE_ID))
								.process(errorInvoiceProcessor)
							.when(header(EDIXmlFeedbackHelper.HEADER_ROUTE_ID).isEqualTo(EDIDesadvRoute.ROUTE_ID_AGGREGATE))
								.process(errorDesadvProcessor)
							.otherwise()
								.log(LoggingLevel.ERROR, "EDI: No available feedback processor found for header[HEADER_ROUTE_ID]=" + header(EDIXmlFeedbackHelper.HEADER_ROUTE_ID))
								.stop() // if there's no error handler, just forget about it...
						.end()
						.log(LoggingLevel.INFO,"EDI: Marshalling error feedback XML Java Object -> XML document...")
						.marshal(new JaxbDataFormat(Constants.JAXB_ContextPath))
						// Add the extension for the Feedback object so that it opens nicely :)
						// (the extension is hard-coded, as i didn't see a real reason to keep it in properties --
						// this can be removed at any time)
						.setHeader(Exchange.FILE_NAME, property(Exchange.FILE_NAME).append(".error.xml"))
						// If errors occurred, put the feedback in the error directory
						.to(EDIFeedbackRoute.EP_EDI_LOCAL_ERROR)
						// Send the feedback to ADempiere
						.log(LoggingLevel.INFO, "EDI: Sending error response to ADempiere...")
						.to(Constants.EP_JMS_TO_AD)
				.end();
		// @formatter:on
	}
}

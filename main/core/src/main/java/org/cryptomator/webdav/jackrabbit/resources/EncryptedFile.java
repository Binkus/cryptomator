/*******************************************************************************
 * Copyright (c) 2014 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/
package org.cryptomator.webdav.jackrabbit.resources;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.DavResource;
import org.apache.jackrabbit.webdav.DavResourceFactory;
import org.apache.jackrabbit.webdav.DavResourceIterator;
import org.apache.jackrabbit.webdav.DavResourceLocator;
import org.apache.jackrabbit.webdav.DavSession;
import org.apache.jackrabbit.webdav.io.InputContext;
import org.apache.jackrabbit.webdav.io.OutputContext;
import org.apache.jackrabbit.webdav.lock.LockManager;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DefaultDavProperty;
import org.cryptomator.crypto.Cryptor;
import org.cryptomator.crypto.exceptions.DecryptFailedException;
import org.cryptomator.webdav.exceptions.IORuntimeException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EncryptedFile extends AbstractEncryptedNode {

	private static final Logger LOG = LoggerFactory.getLogger(EncryptedFile.class);

	public EncryptedFile(DavResourceFactory factory, DavResourceLocator locator, DavSession session, LockManager lockManager, Cryptor cryptor) {
		super(factory, locator, session, lockManager, cryptor);
	}

	@Override
	public boolean isCollection() {
		return false;
	}

	@Override
	public void addMember(DavResource resource, InputContext inputContext) throws DavException {
		throw new UnsupportedOperationException("Can not add member to file.");
	}

	@Override
	public DavResourceIterator getMembers() {
		throw new UnsupportedOperationException("Can not list members of file.");
	}

	@Override
	public void removeMember(DavResource member) throws DavException {
		throw new UnsupportedOperationException("Can not remove member to file.");
	}

	@Override
	public void spool(OutputContext outputContext) throws IOException {
		final Path path = ResourcePathUtils.getPhysicalPath(this);
		if (Files.exists(path)) {
			outputContext.setModificationTime(Files.getLastModifiedTime(path).toMillis());
			outputContext.setProperty(HttpHeader.ACCEPT_RANGES.asString(), HttpHeaderValue.BYTES.asString());
			try (final SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
				outputContext.setContentLength(cryptor.decryptedContentLength(channel));
				if (outputContext.hasStream()) {
					cryptor.decryptedFile(channel, outputContext.getOutputStream());
				}
			} catch (EOFException e) {
				LOG.warn("Unexpected end of stream (possibly client hung up).");
			} catch (DecryptFailedException e) {
				throw new IOException("Error decrypting file " + path.toString(), e);
			}
		}
	}

	@Override
	protected void determineProperties() {
		final Path path = ResourcePathUtils.getPhysicalPath(this);
		if (Files.exists(path)) {
			try (final SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
				final Long contentLength = cryptor.decryptedContentLength(channel);
				properties.add(new DefaultDavProperty<Long>(DavPropertyName.GETCONTENTLENGTH, contentLength));
			} catch (IOException e) {
				LOG.error("Error reading filesize " + path.toString(), e);
				throw new IORuntimeException(e);
			}

			try {
				final BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
				properties.add(new DefaultDavProperty<String>(DavPropertyName.CREATIONDATE, FileTimeUtils.toRfc1123String(attrs.creationTime())));
				properties.add(new DefaultDavProperty<String>(DavPropertyName.GETLASTMODIFIED, FileTimeUtils.toRfc1123String(attrs.lastModifiedTime())));
				properties.add(new HttpHeaderProperty(HttpHeader.ACCEPT_RANGES.asString(), HttpHeaderValue.BYTES.asString()));
			} catch (IOException e) {
				LOG.error("Error determining metadata " + path.toString(), e);
				throw new IORuntimeException(e);
			}
		}
	}

}

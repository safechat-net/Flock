/*
 * *
 *  Copyright (C) 2014 Open Whisper Systems
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.
 * /
 */

package org.anhonesteffort.flock.webdav;

import com.google.common.base.Optional;

import org.apache.commons.httpclient.Header;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.DavServletResponse;
import org.apache.jackrabbit.webdav.MultiStatus;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.Status;
import org.apache.jackrabbit.webdav.client.methods.DeleteMethod;
import org.apache.jackrabbit.webdav.client.methods.PropFindMethod;
import org.apache.jackrabbit.webdav.property.DavProperty;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;
import org.apache.jackrabbit.webdav.property.DavPropertySet;
import org.w3c.dom.Element;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Programmer: rhodey
 */
public abstract class AbstractDavComponentStore <C extends DavComponentCollection<?>>
    implements DavComponentStore<C>
{
  protected final String productId = "OpenWhisperSystems - Flock";

  protected final String           hostHREF;
  protected final String           username;
  protected final String           password;
  protected       Optional<String> currentUserPrincipal = Optional.absent();

  protected DavClient              davClient;
  private   Optional<List<String>> davOptions = Optional.absent();

  public AbstractDavComponentStore(String           hostHREF,
                                   String           username,
                                   String           password,
                                   Optional<String> currentUserPrincipal)
      throws DavException, IOException
  {
    this.hostHREF             = hostHREF;
    this.username             = username;
    this.password             = password;
    this.currentUserPrincipal = currentUserPrincipal;

    this.davClient = new DavClient(new URL(hostHREF), username, password);
  }

  public AbstractDavComponentStore(DavClient client, Optional<String> currentUserPrincipal) {
    this.davClient            = client;
    this.hostHREF             = client.getDavHost().toString();
    this.username             = client.getUsername();
    this.password             = client.getPassword();
    this.currentUserPrincipal = currentUserPrincipal;
  }

  public String getProductId() {
    return productId;
  }

  @Override
  public String getHostHREF() {
    return hostHREF;
  }

  protected String getUserName() {
    return username;
  }

  protected String getPassword() {
    return password;
  }

  public DavClient getClient() {
    return davClient;
  }

  public List<String> getDavOptions() throws DavException, IOException {
    if (!davOptions.isPresent())
      davOptions = Optional.of(davClient.getDavOptions());

    return davOptions.get();
  }

  protected abstract String getWellKnownUri();

  protected Optional<String> getCurrentUserPrincipalFromMultiStatus(MultiStatus multiStatus) {
    for (MultiStatusResponse msResponse : multiStatus.getResponses()) {
      DavPropertySet foundProperties = msResponse.getProperties(DavServletResponse.SC_OK);
      DavProperty    homeSetProperty = foundProperties.get(WebDavConstants.PROPERTY_NAME_CURRENT_USER_PRINCIPAL);

      for (Status status : msResponse.getStatus()) {
        if (status.getStatusCode() == DavServletResponse.SC_OK) {

          if (homeSetProperty != null && homeSetProperty.getValue() instanceof ArrayList) {
            for (Object child : (ArrayList<?>) homeSetProperty.getValue()) {
              if (child instanceof Element) {
                String currentUserPrincipalUri = ((Element) child).getTextContent();
                if (!(currentUserPrincipalUri.endsWith("/")))
                  currentUserPrincipalUri = currentUserPrincipalUri.concat("/");

                return Optional.of(currentUserPrincipalUri);
              }
            }
          }

          // Owncloud :(
          else if (homeSetProperty != null && homeSetProperty.getValue() instanceof Element) {
            String currentUserPrincipalUri = ((Element) homeSetProperty.getValue()).getTextContent();
            if (!(currentUserPrincipalUri.endsWith("/")))
              currentUserPrincipalUri = currentUserPrincipalUri.concat("/");

            return Optional.of(currentUserPrincipalUri);
          }
        }
      }
    }

    return Optional.absent();
  }

  protected Optional<String> getCurrentUserPrincipal(String propFindUri)
      throws IOException, DavException
  {
    DavPropertyNameSet props = new DavPropertyNameSet();
    props.add(WebDavConstants.PROPERTY_NAME_CURRENT_USER_PRINCIPAL);

    PropFindMethod propFindMethod = new PropFindMethod(propFindUri,
                                                       props,
                                                       PropFindMethod.DEPTH_0);

    try {

      davClient.execute(propFindMethod);
      return getCurrentUserPrincipalFromMultiStatus(propFindMethod.getResponseBodyAsMultiStatus());

    } finally {
      propFindMethod.releaseConnection();
    }
  }

  public Optional<String> getCurrentUserPrincipal() throws DavException, IOException {
    if (currentUserPrincipal.isPresent())
      return currentUserPrincipal;

    DavPropertyNameSet props = new DavPropertyNameSet();
    props.add(WebDavConstants.PROPERTY_NAME_CURRENT_USER_PRINCIPAL);

    String         propFindUri    = getHostHREF().concat(getWellKnownUri());
    PropFindMethod propFindMethod = new PropFindMethod(propFindUri,
                                                       props,
                                                       PropFindMethod.DEPTH_0);

    try {

      getClient().execute(propFindMethod);
      return getCurrentUserPrincipalFromMultiStatus(propFindMethod.getResponseBodyAsMultiStatus());

    } catch (DavException e) {

      if (e.getErrorCode() == DavServletResponse.SC_MOVED_PERMANENTLY ||
          e.getErrorCode() == DavServletResponse.SC_MOVED_TEMPORARILY ||
          e.getErrorCode() == DavServletResponse.SC_SEE_OTHER         ||
          e.getErrorCode() == DavServletResponse.SC_USE_PROXY         ||
          e.getErrorCode() == DavServletResponse.SC_TEMPORARY_REDIRECT)
      {
        Header locationHeader = propFindMethod.getResponseHeader("location"); // TODO: find constant for this...
        if (locationHeader.getValue() != null) {
          currentUserPrincipal = getCurrentUserPrincipal(locationHeader.getValue());
          return currentUserPrincipal;
        }
      }
      else
        throw e;

    } finally {
      propFindMethod.releaseConnection();
    }

    return Optional.absent();
  }

  @Override
  public void removeCollection(String path) throws DavException, IOException {
    DeleteMethod deleteMethod = new DeleteMethod(getHostHREF().concat(path));

    try {

      getClient().execute(deleteMethod);

      if (!deleteMethod.succeeded() && deleteMethod.getStatusCode() != DavServletResponse.SC_OK)
        throw new DavException(deleteMethod.getStatusCode(), deleteMethod.getStatusText());

    } finally {
      deleteMethod.releaseConnection();
    }
  }

  @Override
  public void closeHttpConnection() {
    davClient.closeHttpConnection();
  }

}

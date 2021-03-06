/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.opencastproject.index.service.catalog.adapter;

import static org.opencastproject.index.service.catalog.adapter.CatalogUIAdapterFactory.CONF_FLAVOR_KEY;
import static org.opencastproject.index.service.catalog.adapter.CatalogUIAdapterFactory.CONF_ORGANIZATION_KEY;
import static org.opencastproject.index.service.catalog.adapter.CatalogUIAdapterFactory.CONF_TITLE_KEY;
import static org.opencastproject.util.OsgiUtil.getCfg;

import org.opencastproject.index.service.resources.list.api.ListProvidersService;
import org.opencastproject.mediapackage.EName;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.metadata.dublincore.CatalogUIAdapter;
import org.opencastproject.metadata.dublincore.DublinCore;
import org.opencastproject.metadata.dublincore.DublinCoreCatalog;
import org.opencastproject.metadata.dublincore.DublinCoreValue;
import org.opencastproject.metadata.dublincore.MetadataCollection;
import org.opencastproject.metadata.dublincore.MetadataField;
import org.opencastproject.metadata.dublincore.SeriesCatalogUIAdapter;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.osgi.service.cm.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class ConfigurableDCCatalogUIAdapter implements CatalogUIAdapter {

  private static final Logger logger = LoggerFactory.getLogger(ConfigurableDCCatalogUIAdapter.class);

  /** The catalog UI adapter configuration */
  protected CatalogUIAdapterConfiguration config;

  /** The organization name */
  protected String organization;

  /** The flavor of this catalog */
  protected MediaPackageElementFlavor flavor;

  /** The title of this catalog */
  protected String title;

  /** The metadata fields for all properties of the underlying DublinCore */
  protected Map<String, MetadataField<?>> dublinCoreProperties;

  /** Reference to the list providers service */
  protected ListProvidersService listProvidersService;

  /**
   * Reconfigures the {@link SeriesCatalogUIAdapter} instance with an updated set of configuration properties;
   *
   * @param properties
   *          the configuration properties
   * @throws ConfigurationException
   *           if there is a configuration error
   */
  public void updated(Dictionary<String, ?> properties) throws ConfigurationException {
    config = CatalogUIAdapterConfiguration.loadFromDictionary(properties);
    organization = getCfg(properties, CONF_ORGANIZATION_KEY);
    flavor = MediaPackageElementFlavor.parseFlavor(getCfg(properties, CONF_FLAVOR_KEY));
    title = getCfg(properties, CONF_TITLE_KEY);
    dublinCoreProperties = DublinCoreMetadataUtil.getDublinCoreProperties(properties);
  }

  @Override
  public DublinCoreMetadataCollection getRawFields() {
    DublinCoreMetadataCollection rawFields = new DublinCoreMetadataCollection();
    for (MetadataField metadataField : dublinCoreProperties.values()) {
      try {
        rawFields.addField(metadataField, listProvidersService);
      } catch (IllegalArgumentException e) {
        logger.error("Skipping metadata field '{}' because of error", metadataField, e);
      }
    }
    return rawFields;
  }

  protected MetadataCollection getFieldsFromCatalogs(List<DublinCoreCatalog> dcCatalogs) {
    Map<String,List<MetadataField<?>>> metadataFields = new HashMap();
    List<MetadataField<?>> emptyFields = new ArrayList(dublinCoreProperties.values());

    for (MetadataField<?> metadataField: dublinCoreProperties.values()) {

      String namespace = DublinCore.TERMS_NS_URI;
      if (metadataField.getNamespace().isSome()) {
        namespace = metadataField.getNamespace().get();
      }

      String metadataFieldKey = namespace.toLowerCase() + ":" + metadataField.getInputID().toLowerCase();

      List<MetadataField<?>> metadataFieldList = metadataFields.computeIfAbsent(metadataFieldKey,
              key -> new ArrayList<>());
      metadataFieldList.add(metadataField);
    }

    DublinCoreMetadataCollection dublinCoreMetadata = new DublinCoreMetadataCollection();
    for (DublinCoreCatalog dc : dcCatalogs) {

      for (EName propertyKey : dc.getValues().keySet()) {

        // namespace and input id need to match
        String metadataFieldKey = propertyKey.getNamespaceURI().toLowerCase() + ":"
                + propertyKey.getLocalName().toLowerCase();
        if (metadataFields.containsKey(metadataFieldKey)) {

          // multiple metadata fields can match
          for (MetadataField<?> metadataField : metadataFields.get(metadataFieldKey)) {
            List<DublinCoreValue> values = dc.get(propertyKey);
            if (!values.isEmpty()) {
              try {
                dublinCoreMetadata.addField(metadataField, values.stream().map(DublinCoreValue::getValue).collect(Collectors.toList()),
                        getListProvidersService());
                emptyFields.remove(metadataField);
              } catch (IllegalArgumentException e) {
                logger.error("Skipping metadata field '{}' because of error: {}", metadataField.getInputID(),
                        ExceptionUtils.getStackTrace(e));
              }
            }
          }
        }
      }
    }

    // Add all of the rest of the fields that didn't have values as empty.
    for (MetadataField metadataField: emptyFields) {
      try {
        dublinCoreMetadata.addField(metadataField, getListProvidersService());
      } catch (IllegalArgumentException e) {
        logger.error("Skipping metadata field '{}' because of error", metadataField, e);
      }
    }
    return dublinCoreMetadata;
  }

  @Override
  public String getOrganization() {
    return organization;
  }

  @Override
  public MediaPackageElementFlavor getFlavor() {
    return flavor;
  }

  @Override
  public String getUITitle() {
    return title;
  }

  public void setListProvidersService(ListProvidersService listProvidersService) {
    this.listProvidersService = listProvidersService;
  }

  protected ListProvidersService getListProvidersService() {
    return listProvidersService;
  }
}

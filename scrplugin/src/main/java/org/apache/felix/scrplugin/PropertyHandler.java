/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.scrplugin;

import java.util.*;

import org.apache.felix.scrplugin.om.Component;
import org.apache.felix.scrplugin.om.Property;
import org.apache.felix.scrplugin.om.metatype.AttributeDefinition;
import org.apache.felix.scrplugin.om.metatype.OCD;
import org.apache.felix.scrplugin.tags.JavaField;
import org.apache.felix.scrplugin.tags.JavaTag;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.StringUtils;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 * Utility class for handling the properties.
 */
public class PropertyHandler {

    /** This is a map using the name as the key and {@link PropertyDescription}
     * as values.
     */
    final private Map properties = new HashMap();

    /**
     * @param property
     * @param name
     * @param component
     * @param ocd
     */
    public void doProperty(JavaTag property, String name, Component component, OCD ocd, JavaField javaField)
    throws MojoExecutionException {
        final Property prop = new Property(property);
        prop.setName(name);
        prop.setType(property.getNamedParameter(Constants.PROPERTY_TYPE));
        // let's first check for a value attribute
        final String value = property.getNamedParameter(Constants.PROPERTY_VALUE);
        if ( value != null ) {
            prop.setValue(value);
        } else {
            // now we check for a value ref attribute
            final String valueRef = property.getNamedParameter(Constants.PROPERTY_VALUE_REF);
            if ( valueRef != null ) {
                this.setPropertyValueRef(property, prop, valueRef);
            } else {
                // check for multivalue - these can either be values or value refs
                final List values = new ArrayList();
                final Map valueMap = property.getNamedParameterMap();
                for (Iterator vi = valueMap.entrySet().iterator(); vi.hasNext();) {
                    final Map.Entry entry = (Map.Entry) vi.next();
                    final String key = (String) entry.getKey();
                    if (key.startsWith(Constants.PROPERTY_MULTIVALUE_PREFIX) ) {
                        values.add(entry.getValue());
                    } else if ( key.startsWith(Constants.PROPERTY_MULTIVALUE_REF_PREFIX) ) {
                        final String[] stringValues = this.getPropertyValueRef(property, prop, (String)entry.getValue());
                        if ( stringValues != null ) {
                            for(int i=0; i<stringValues.length; i++) {
                                values.add(stringValues[i]);
                            }
                        }
                    }
                }
                if ( values.size() > 0 ) {
                    prop.setMultiValue((String[])values.toArray(new String[values.size()]));
                } else {
                    // we have no value, valueRef or values so let's try to
                    // get the value of the field if a name attribute is specified
                    if ( property.getNamedParameter(Constants.PROPERTY_NAME) != null && javaField != null ) {
                        this.setPropertyValueRef(property, prop, javaField.getName());
                    }
                }
            }
        }

        // property is private if explicitly marked or a well known
        // service property such as service.pid
        final boolean isPrivate = SCRDescriptorMojo.getBoolean(property,
            Constants.PROPERTY_PRIVATE, false)
            || name.equals(org.osgi.framework.Constants.SERVICE_PID)
            || name.equals(org.osgi.framework.Constants.SERVICE_DESCRIPTION)
            || name.equals(org.osgi.framework.Constants.SERVICE_ID)
            || name.equals(org.osgi.framework.Constants.SERVICE_RANKING)
            || name.equals(org.osgi.framework.Constants.SERVICE_VENDOR)
            || name.equals(ConfigurationAdmin.SERVICE_BUNDLELOCATION)
            || name.equals(ConfigurationAdmin.SERVICE_FACTORYPID);

        // if this is a public property and the component is generating metatype info
        // store the information!
        if ( !isPrivate && ocd != null ) {
            final AttributeDefinition ad = new AttributeDefinition();
            ocd.getProperties().add(ad);
            ad.setId(prop.getName());
            ad.setType(prop.getType());

            String adName = property.getNamedParameter(Constants.PROPERTY_LABEL);
            if ( adName == null ) {
                adName = "%" + prop.getName() + ".name";
            }
            ad.setName(adName);
            String adDesc = property.getNamedParameter(Constants.PROPERTY_DESCRIPTION);
            if ( adDesc == null ) {
                adDesc = "%" + prop.getName() + ".description";
            }
            ad.setDescription(adDesc);
            // set optional multivalues, cardinality might be overwritten by setValues !!
            final String cValue = property.getNamedParameter(Constants.PROPERTY_CARDINALITY);
            if (cValue != null) {
                if ("-".equals(cValue)) {
                    // unlimited vector
                    ad.setCardinality(new Integer(Integer.MIN_VALUE));
                } else if ("+".equals(cValue)) {
                   // unlimited array
                    ad.setCardinality(new Integer(Integer.MAX_VALUE));
                } else {
                    try {
                        ad.setCardinality(Integer.valueOf(cValue));
                    } catch (NumberFormatException nfe) {
                        // default to scalar in case of conversion problem
                    }
                }
            }
            ad.setDefaultValue(prop.getValue());
            ad.setDefaultMultiValue(prop.getMultiValue());

            // check options
            String[] parameters = property.getParameters();
            Map options = null;
            for (int j=0; j < parameters.length; j++) {
                if (Constants.PROPERTY_OPTIONS.equals(parameters[j])) {
                    options = new LinkedHashMap();
                } else if (options != null) {
                    String optionLabel = parameters[j];
                    String optionValue = (j < parameters.length-2) ? parameters[j+2] : null;
                    if (optionValue != null) {
                        options.put(optionLabel, optionValue);
                    }
                    j += 2;
                }
            }
            ad.setOptions(options);
        }

        component.addProperty(prop);
    }

    /**
     * Return the name of the property.
     * @param property
     * @param defaultName
     * @return The name of the property or the defaultName
     */
    public String getPropertyName(JavaTag property, String defaultName) {
        final String name = property.getNamedParameter(Constants.PROPERTY_NAME);
        if (!StringUtils.isEmpty(name)) {
            return name;
        }

        return defaultName;
    }

    public void setPropertyValueRef(final JavaTag tag, Property property, String valueRef)
    throws MojoExecutionException {
        final String[] values = this.getPropertyValueRef(tag, property, valueRef);
        if ( values != null && values.length == 1 ) {
            property.setValue(values[0]);
        } else if ( values != null && values.length > 1 ) {
            property.setMultiValue(values);
        }
    }

    public String[] getPropertyValueRef(final JavaTag tag, Property prop, String valueRef)
    throws MojoExecutionException {
        int classSep = valueRef.lastIndexOf('.');
        if ( classSep == -1 ) {
            // local variable
            // TODO - This could be a static import!
            final JavaField field = tag.getJavaClassDescription().getFieldByName(valueRef);
            if ( field == null ) {
                throw new MojoExecutionException("Property references unknown field " + valueRef + " in class " + tag.getJavaClassDescription().getName());
            }
            // determine type (if not set explicitly)
            if ( prop.getType() == null ) {
                final String type = field.getType();
                if ( "java.lang.String".equals(type) ) {
                    prop.setType("String");
                } else if ("java.lang.Long".equals(type) || "long".equals(type) ) {
                    prop.setType("Long");
                } else if ("java.lang.Double".equals(type) || "double".equals(type) ) {
                    prop.setType("Double");
                } else if ("java.lang.Float".equals(type) || "float".equals(type) ) {
                    prop.setType("Float");
                } else if ("java.lang.Integer".equals(type) || "int".equals(type) ) {
                    prop.setType("Integer");
                } else if ("java.lang.Byte".equals(type) || "byte".equals(type) ) {
                    prop.setType("Byte");
                } else if ("java.lang.Character".equals(type) || "char".equals(type) ) {
                    prop.setType("Char");
                } else if ("java.lang.Boolean".equals(type) || "boolean".equals(type) ) {
                    prop.setType("Boolean");
                } else if ("java.lang.Short".equals(type) || "short".equals(type) ) {
                    prop.setType("Short");
                }

            }
            return field.getInitializationExpression();
        }
        throw new MojoExecutionException("Referencing values from foreign classes not supported yet.");
    }

    public void testProperty(JavaTag property, String defaultName, JavaField field, boolean isInspectedClass)
    throws MojoExecutionException {
        final String propName = this.getPropertyName(property, defaultName);

        if ( propName != null ) {
            if ( properties.containsKey(propName) ) {
                // if the current class is the class we are currently inspecting, we
                // have found a duplicate definition
                if ( isInspectedClass ) {
                    throw new MojoExecutionException("Duplicate definition for property " + propName + " in class " + property.getJavaClassDescription().getName());
                }
            } else {
                properties.put(propName, new PropertyDescription(property, field));
            }
        }
    }

    public void handleField(JavaField javaField, boolean isInspectedClass)
    throws MojoExecutionException {
        final JavaTag tag = javaField.getTagByName(Constants.PROPERTY);
        if (tag != null) {
            String defaultName = null;
            if ( "java.lang.String".equals(javaField.getType()) ) {
                final String[] initValues = javaField.getInitializationExpression();
                if ( initValues != null && initValues.length == 1 ) {
                    defaultName = initValues[0];
                }
            }
            this.testProperty(tag, defaultName, javaField, isInspectedClass);
        }
    }

    public void processProperties(final Component component, final OCD ocd)
    throws MojoExecutionException {
        final Iterator propIter = properties.entrySet().iterator();
        while ( propIter.hasNext() ) {
            final Map.Entry entry = (Map.Entry)propIter.next();
            final String propName = entry.getKey().toString();
            final PropertyDescription desc = (PropertyDescription)entry.getValue();
            this.doProperty(desc.propertyTag, propName, component, ocd, desc.field);
        }
    }

    protected static final class PropertyDescription {
        public final JavaTag propertyTag;
        public final JavaField field;

        public PropertyDescription(final JavaTag p, final JavaField f) {
            this.propertyTag = p;
            this.field = f;
        }
    }
}

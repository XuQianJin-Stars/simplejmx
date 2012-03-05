package com.j256.simplejmx;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.ReflectionException;

/**
 * Wrapping of an object so we can dynamically expose its attributes and operations using annotations and reflection.
 * 
 * @author graywatson
 */
public class ReflectionMbean implements DynamicMBean {

	private final Object obj;
	private final Map<String, Method> fieldGetMap = new HashMap<String, Method>();
	private final Map<String, Method> fieldSetMap = new HashMap<String, Method>();
	private final Map<String, Method> fieldOperationMap = new HashMap<String, Method>();
	private final MBeanInfo mbeanInfo;

	public ReflectionMbean(Object obj) {
		this.obj = obj;
		this.mbeanInfo = buildMbeanInfo(obj);
	}

	public Object getAttribute(String attribute) throws AttributeNotFoundException, ReflectionException {
		Method method = fieldGetMap.get(attribute);
		if (method == null) {
			throw new AttributeNotFoundException("Unknown attribute " + attribute);
		} else {
			try {
				return method.invoke(obj);
			} catch (Exception e) {
				throw new ReflectionException(e);
			}
		}
	}

	public AttributeList getAttributes(String[] attributeNames) {
		AttributeList returnList = new AttributeList();
		for (String name : attributeNames) {
			try {
				returnList.add(new Attribute(name, getAttribute(name)));
			} catch (Exception e) {
				returnList.add(new Attribute(name, e.getMessage()));
			}
		}
		return returnList;
	}

	public MBeanInfo getMBeanInfo() {
		return mbeanInfo;
	}

	public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException,
			ReflectionException {
		Method method = fieldOperationMap.get(actionName);
		if (method == null) {
			throw new MBeanException(new IllegalArgumentException("Unknown action " + actionName));
		} else {
			try {
				return method.invoke(obj, params);
			} catch (Exception e) {
				throw new ReflectionException(e);
			}
		}
	}

	public void setAttribute(Attribute attribute) throws AttributeNotFoundException, ReflectionException {
		Method method = fieldSetMap.get(attribute.getName());
		if (method == null) {
			throw new AttributeNotFoundException("Unknown attribute " + attribute);
		} else {
			try {
				method.invoke(obj, attribute.getValue());
			} catch (Exception e) {
				throw new ReflectionException(e);
			}
		}
	}

	public AttributeList setAttributes(AttributeList attributes) {
		AttributeList returnList = new AttributeList();
		for (Attribute attribute : attributes.asList()) {
			String name = attribute.getName();
			try {
				setAttribute(attribute);
				returnList.add(new Attribute(name, getAttribute(name)));
			} catch (Exception e) {
				returnList.add(new Attribute(name, e.getMessage()));
			}
		}
		return returnList;
	}

	/**
	 * Build our JMX information object by using reflection.
	 */
	private MBeanInfo buildMbeanInfo(Object obj) {
		JmxResource jmxResource = obj.getClass().getAnnotation(JmxResource.class);
		String desc;
		if (jmxResource == null || jmxResource.description() == null || jmxResource.description().length() == 0) {
			desc = "Jmx information about " + obj.getClass();
		} else {
			desc = jmxResource.description();
		}
		findAttributes(obj);
		List<MBeanAttributeInfo> attributes = new ArrayList<MBeanAttributeInfo>();
		List<MBeanOperationInfo> operations = new ArrayList<MBeanOperationInfo>();
		// we have to go back because we need to match up the getters and setters
		for (Method method : obj.getClass().getMethods()) {
			JmxAttribute jmxAttribute = method.getAnnotation(JmxAttribute.class);
			JmxOperation jmxOperation = method.getAnnotation(JmxOperation.class);
			String name = method.getName();
			if (jmxAttribute != null) {
				String varName = buildMethodSuffix(name);
				Method getMethod = fieldGetMap.get(varName);
				if (name.startsWith("set") && getMethod != null) {
					// don't stick in 2 of the them
					continue;
				}
				Method setMethod = fieldSetMap.get(varName);
				try {
					attributes.add(new MBeanAttributeInfo(varName, jmxAttribute.description(), getMethod, setMethod));
				} catch (IntrospectionException e) {
					// ignore this attribute I guess
				}
			} else if (jmxOperation != null) {
				if (name.startsWith("get") || name.startsWith("set")) {
					throw new IllegalArgumentException("Operation method " + method
							+ " cannot start with get or set.  Is this an attribute?");
				}
				if (fieldOperationMap.containsKey(name)) {
					throw new IllegalArgumentException("Another method " + method
							+ " has been marked as an operation with different parameters");
				}
				fieldOperationMap.put(name, method);
				operations.add(new MBeanOperationInfo(jmxOperation.description(), method));
			}
		}

		return new MBeanInfo(obj.getClass().getName(), desc,
				attributes.toArray(new MBeanAttributeInfo[attributes.size()]), null,
				operations.toArray(new MBeanOperationInfo[operations.size()]), null);
	}

	/**
	 * Using reflection, find methods from our object that will be exposed via JMX.
	 */
	private void findAttributes(Object obj) {
		for (Method method : obj.getClass().getMethods()) {
			JmxAttribute jmxAttribute = method.getAnnotation(JmxAttribute.class);
			if (jmxAttribute == null) {
				// skip it if no annotation
				continue;
			}
			String name = method.getName();
			if (name.length() < 5) {
				throw new IllegalArgumentException("Method " + method
						+ " is marked as an attribute but its name is too short");
			}
			String varName = buildMethodSuffix(name);
			Map<String, Method> fieldMap;
			if (name.startsWith("get")) {
				if (method.getParameterTypes().length != 0) {
					throw new IllegalArgumentException("Method " + method
							+ " is marked as an attribute but the get has arguments");
				}
				if (method.getReturnType() == void.class) {
					throw new IllegalArgumentException("Method " + method
							+ " is marked as a get attribute but does not return anything");
				}
				fieldMap = fieldGetMap;
			} else if (name.startsWith("set")) {
				if (method.getParameterTypes().length != 1) {
					throw new IllegalArgumentException("Method " + method
							+ " is marked as an attribute but the set does not have 1 argument");
				}
				if (method.getReturnType() != void.class) {
					throw new IllegalArgumentException("Method " + method
							+ " is marked as a set attribute but does not return void");
				}
				fieldMap = fieldSetMap;
			} else {
				throw new IllegalArgumentException("Method " + method
						+ " is marked as an attribute but is not get... or set...");
			}
			fieldMap.put(varName, method);
		}
	}

	private String buildMethodSuffix(String name) {
		return Character.toLowerCase(name.charAt(3)) + name.substring(4);
	}
}

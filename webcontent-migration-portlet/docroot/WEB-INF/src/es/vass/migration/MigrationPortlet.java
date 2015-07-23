package es.vass.migration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.PortletException;
import javax.xml.ws.Action;

import com.liferay.portal.kernel.xml.Document;
import com.liferay.portal.kernel.xml.Element;
import com.liferay.portal.kernel.xml.Node;
import com.liferay.portal.kernel.xml.SAXReaderUtil;
import com.liferay.portal.kernel.xml.XPath;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.ServiceContextFactory;
import com.liferay.portlet.dynamicdatamapping.model.DDMStructure;
import com.liferay.portlet.dynamicdatamapping.model.DDMTemplate;
import com.liferay.portlet.dynamicdatamapping.service.DDMStructureLocalServiceUtil;
import com.liferay.portlet.dynamicdatamapping.service.DDMTemplateLocalServiceUtil;
import com.liferay.portlet.journal.model.JournalArticle;
import com.liferay.portlet.journal.service.JournalArticleLocalServiceUtil;
import com.liferay.util.bridges.mvc.MVCPortlet;

public class MigrationPortlet extends MVCPortlet {

	private final static long TEMPLATE_CLASS_ID = 10101;
	
	@Action
	public void checkAction(ActionRequest actionRequest, ActionResponse actionResponse) throws IOException, PortletException {
		StringBuffer output = new StringBuffer();
		output.append("<h4>Check structures without saving</h4>\n");
		getStructuresWithRepeatedFields(actionRequest.getLocale(), output);
		actionResponse.setRenderParameter("PROCESS_OUTPUT", output.toString());
	}

	@Action
	public void checkAllAction(ActionRequest actionRequest, ActionResponse actionResponse) throws IOException, PortletException {
		StringBuffer output = new StringBuffer();
		output.append("<h4>Check structures and contents without saving</h4>\n");
		List<StructureToChange> scs = getStructuresWithRepeatedFields(actionRequest.getLocale(), output);
		doChanges(actionRequest, scs, false, output);
		actionResponse.setRenderParameter("PROCESS_OUTPUT", output.toString());
	}

	@Action
	public void runAction(ActionRequest actionRequest, ActionResponse actionResponse) throws IOException, PortletException {
		StringBuffer output = new StringBuffer();
		output.append("<h4>Change all structures and contents</h4>\n");
		List<StructureToChange> scs = getStructuresWithRepeatedFields(actionRequest.getLocale(), output);
		doChanges(actionRequest, scs, true, output);
		actionResponse.setRenderParameter("PROCESS_OUTPUT", output.toString());
	}
	
	private List<StructureToChange> getStructuresWithRepeatedFields(Locale locale, StringBuffer output) {
		try {
			output.append("<br />\n");
			output.append("[CHECKING STRUCTURES]<br />\n");
			List<StructureToChange> scs = new ArrayList<StructureToChange>();
			List<DDMStructure> all = DDMStructureLocalServiceUtil.getStructures();
			for (DDMStructure structure : all) {
				StructureToChange sc = null;
				List<String> datePaths = new ArrayList<String>();
				output.append("CHECKING STRUCTURE \"" + structure.getName(locale) + "\" ...<br />\n");
				output.append("<ul>\n");
				Document doc = structure.getDocument();
				XPath xPathSelector = SAXReaderUtil.createXPath("//dynamic-element");
				List<Node> nodes = xPathSelector.selectNodes(doc);
				Map<String, List<String>> xpaths = new HashMap<String, List<String>>();
				output.append("<li>Found " + nodes.size() + " fields</li>\n");
				for (Node node : nodes) {
					Element element = (Element)node.asXPathResult(node.getParent());
					String xpath = getNodeXPath(element);
					String fname = element.attributeValue("name");
					if (xpaths.get(fname) == null) {xpaths.put(fname, new ArrayList<String>());}
					xpaths.get(fname).add(xpath);
					String ftype = element.attributeValue("type");
					if ((ftype != null)&&(ftype.equalsIgnoreCase("date"))) {datePaths.add(xpath);}
				}
				Set<String> keys = xpaths.keySet();
				for (String key : keys) {
					List<String> fxpaths = xpaths.get(key);
					if (fxpaths.size() > 1) {
						if (sc == null) {sc = new StructureToChange(structure, new ArrayList<StructureFieldInfo>(), datePaths);}
						sc.getFields().add(new StructureFieldInfo(key, fxpaths));
						output.append("<li>Found a repeated field (\"" + key + "\") with the following paths:</li>\n");
						output.append("<li><ul>");
						for (String fxpath : fxpaths) {output.append("<li>" + fxpath + "</li>");}
						output.append("</ul></li>\n");
					}
				}
				if ((sc == null)&&(datePaths.size() > 0)) {
					sc =  new StructureToChange(structure, new ArrayList<StructureFieldInfo>(), datePaths);
					output.append("<li>Found some date-type fields in the following paths:</li>\n");
					output.append("<li><ul>");
					for (String fxpath : datePaths) {output.append("<li>" + fxpath + "</li>");}
					output.append("</ul></li>\n");
				}
				if (sc == null) {output.append("<li>No repeated or date-type fields found, the structure is good as it is</li>\n");}
				else {
					scs.add(sc);
					if (sc.getFields().size() > 0) {output.append("<li>" + sc.getFields().size() + " repeated fields found</li>\n");}
					if (sc.getDatePaths().size() > 0) {output.append("<li>" + sc.getDatePaths().size() + " date-type fields found</li>\n");}
					output.append("<li>This structure should be updated</li>\n");
				}
				output.append("</ul>\n");
				output.append("END OF STRUCTURE CHECK<br />\n");
			}
			output.append("[END OF STRUCTURE CHECKING]<br />\n");
			return scs;
		} catch (Exception ex) {ex.printStackTrace();}
		return null;
	}
	
	private void doChanges(ActionRequest request, List<StructureToChange> scs, boolean change, StringBuffer output) {
		if (scs == null) {return;}
		try {
			Locale locale = request.getLocale();
			ServiceContext serviceContext = ServiceContextFactory.getInstance(request);
			output.append("<br />\n");
			output.append("[CHANGING STRUCTURES AND RELATED CONTENTS]<br />\n");
			List<TemplateToChange> templates = new ArrayList<TemplateToChange>();
			for (StructureToChange sc : scs) {
				if (sc.getFields().size() > 0) {
					String structureName = sc.getStructure().getName(locale);
					output.append("GETTING CONTENTS FOR STRUCTURE " + structureName + "...<br />\n");
					output.append("<ul>\n");
					List<JournalArticle> articles = JournalArticleLocalServiceUtil.getStructureArticles(new String[] {sc.getStructure().getStructureKey()});
					output.append("<li>Found " + articles.size() + " web contents</li>\n");
					for (JournalArticle article : articles) {
						TemplateToChange ttc = TemplateToChange.addToList(article.getGroupId(), article.getTemplateId(), templates);
						if (ttc != null) {ttc.addStructure(structureName);}
						output.append("<li>Analyzing web content \"" + article.getTitle(locale) + "\" (v" + article.getVersion() + ", status " + article.getStatus() + ")</li>\n");
						output.append("<li>\n");
						output.append("<ul>\n");
						Document doc = SAXReaderUtil.read(article.getContent());
						for (StructureFieldInfo finfo : sc.getFields()) {
							output.append("<li>Applying changes for fields with name \"" + finfo.getFieldName() + "\"</li>\n");
							output.append("<li><ul>");
							int counter = 0;
							for (String xpath : finfo.getXPaths()) {
								xpath = "/root" + xpath;
								XPath xPathSelector = SAXReaderUtil.createXPath(xpath);
								List<Node> nodes = xPathSelector.selectNodes(doc);
								if (counter == 0) {
									output.append("<li>Skipping nodes (" + nodes.size() + ") of path " + xpath + "</li>\n");
								} else {
									String newName = finfo.getFieldName() + "_" + (counter + 1);
									if (ttc != null) {ttc.addField(newName, xpath);}
									if (change) {
										output.append("<li>Changing nodes (" + nodes.size() + ") to name \"" + newName + "\" for path " + xpath + "</li>\n");
										for (Node node : nodes) {
											Element element = (Element)node.asXPathResult(node.getParent());
											element.attribute("name").setValue(newName);
										}
									} else {
										output.append("<li>Should change nodes (" + nodes.size() + ") to name \"" + newName + "\" for path " + xpath + "</li>\n");
									}
								}
								counter++;
							}
							output.append("</ul></li>\n");
						}
						if (change) {
							output.append("<li>Saving web content... ");
							try {
								JournalArticleLocalServiceUtil.updateArticle(article.getUserId(), article.getGroupId(), article.getFolderId(), article.getArticleId(),
										article.getVersion(), doc.asXML(), serviceContext);
								output.append("OK");
							} catch (Exception ex) {output.append("FAILED, error: " + ex.getClass().getName());}
							output.append("</li>");
						}
						output.append("</ul>\n");
						output.append("</li>\n");
					}
					output.append("</ul>\n");
					output.append("END OF STRUCTURE CONTENTS<br />\n");
				}
				output.append("CHANGING STRUCTURE<br />\n");
				output.append("<ul>\n");
				Document doc = sc.getStructure().getDocument();
				if (sc.getFields().size() > 0) {
					for (StructureFieldInfo finfo : sc.getFields()) {
						output.append("<li>Applying changes for fields with name \"" + finfo.getFieldName() + "\"</li>\n");
						output.append("<li><ul>");
						int counter = 0;
						for (String xpath : finfo.getXPaths()) {
							xpath = "/root" + xpath;
							XPath xPathSelector = SAXReaderUtil.createXPath(xpath);
							List<Node> nodes = xPathSelector.selectNodes(doc);
							if (counter == 0) {output.append("<li>Skipping nodes (" + nodes.size() + ") of path " + xpath + "</li>\n");}
							else {
								String newName = finfo.getFieldName() + "_" + (counter + 1);
								if (change) {
									output.append("<li>Changing nodes (" + nodes.size() + ") to name \"" + newName + "\" for path " + xpath + "</li>\n");
									for (Node node : nodes) {
										Element element = (Element)node.asXPathResult(node.getParent());
										element.attribute("name").setValue(newName);
									}
								} else {
									output.append("<li>Should change nodes (" + nodes.size() + ") to name \"" + newName + "\" for path " + xpath + "</li>\n");
								}
							}
							counter++;
						}
						output.append("</ul></li>\n");
					}
				}
				if (sc.getDatePaths().size() > 0) {
					for (String dxpath : sc.getDatePaths()) {
						if (change) {
							output.append("<li>Updating type to ddm-date for node \"" + dxpath + "\"... ");
							String xpath = "/root" + dxpath;
							XPath xPathSelector = SAXReaderUtil.createXPath(xpath);
							List<Node> nodes = xPathSelector.selectNodes(doc);
							for (Node node : nodes) {
								Element element = (Element)node.asXPathResult(node.getParent());
								element.attribute("type").setValue("ddm-date");
							}
							output.append("OK</li>\n");
						} else {
							output.append("<li>Should change node \"" + dxpath + "\" to type \"ddm-date\"</li>\n");
						}
					}
				}
				if (change) {
					output.append("<li>Saving structure... ");
					try {
						sc.getStructure().setXsd(doc.asXML());
						DDMStructureLocalServiceUtil.updateDDMStructure(sc.getStructure());
						output.append("OK");
					} catch (Exception ex) {output.append("FAILED, error: " + ex.getClass().getName());}
					output.append("</li>");
				}
				output.append("</ul>\n");
				output.append("END OF STRUCTURE CHANGE<br />\n");
			}
			output.append("[END OF STRUCTURE CHANGES]<br />\n");
			output.append("[AFFECTED TEMPLATES]<br />\n");
			output.append("<ul>\n");
			for (TemplateToChange template : templates) {
				DDMTemplate tinst = template.getTemplate(output);
				if (tinst != null) {
					output.append("<li>Changes to apply to template \"" + tinst.getName(locale) + "\"</li>");
					output.append("<li>(changed structures: " + template.getStructureNames() + ")</li>");
					output.append("<li><ul>");
					for (TemplateFieldInfo tinfo : template.getFields()) {
						output.append("<li>Change field \"" + tinfo.getXPath() + "\" to \"" + tinfo.getFieldName() + "\"</li>");
					}
					output.append("</ul></li>\n");
				}
			} 
			output.append("</ul>\n");
			output.append("[END OF AFFECTED TEMPLATES]<br />\n");
		} catch (Exception ex) {ex.printStackTrace();}
	}
	
	private final static String getNodeXPath(Element element) {
		String base = "";
		Element parent = element.getParent();
		if ((parent != null)&&(parent.getName().equalsIgnoreCase("dynamic-element"))) {base = getNodeXPath(parent);}
		return base + "/dynamic-element[@name=\"" + element.attributeValue("name") + "\"]";
	}

	private final static class StructureFieldInfo {

		private String fieldName;
		private List<String> xPaths;
		
		public StructureFieldInfo(String fieldName, List<String> xPaths) {
			this.fieldName = fieldName;
			this.xPaths = xPaths;
		}
		
		public String getFieldName() {return fieldName;}
		public List<String> getXPaths() {return xPaths;}
	}
	
	private final static class TemplateFieldInfo {
		
		private String fieldName;
		private String xPath;
		
		public TemplateFieldInfo(String fieldName, String xPath) {
			this.fieldName = fieldName;
			this.xPath = xPath;
		}
		
		public String getFieldName() {return fieldName;}
		public String getXPath() {return xPath;}
		
	}
	
	private final static class TemplateToChange {
		
		private long groupId;
		private String templateRef;
		private List<String> structures = new ArrayList<String>();
		private List<TemplateFieldInfo> fields = new ArrayList<TemplateFieldInfo>();
		
		public TemplateToChange(long groupId, String templateRef) {
			this.groupId = groupId;
			this.templateRef = templateRef;
		}
		
		public long getGroupId() {return groupId;}
		public String getTemplateRef() {return templateRef;}
		public List<String> getStructures() {return structures;}
		public List<TemplateFieldInfo> getFields() {return fields;}
		
		public DDMTemplate getTemplate(StringBuffer output) {
			DDMTemplate template = null;
			try {template = DDMTemplateLocalServiceUtil.getTemplate(groupId, TEMPLATE_CLASS_ID, templateRef, true);}
			catch (Exception ex) {output.append("<li>" + ex.getClass().getName() + ": " + ex.getMessage() + "</li>");}
			return template; 
		}
		
		public void addStructure(String sname) {
			if (!structures.contains(sname)) {structures.add(sname);}
		}
		
		public String getStructureNames() {
			StringBuffer sb = new StringBuffer();
			for (String structure : structures) {
				if (!sb.toString().equals("")) {sb.append(", ");}
				sb.append("\"" + structure + "\"");
			}
			return sb.toString();
		}
		
		public void addField(String fieldName, String xPath) {
			fields.add(new TemplateFieldInfo(fieldName, xPath));
		}
		
		public final static TemplateToChange addToList(long groupId, String templateRef, List<TemplateToChange> ttcs) {
			if (ttcs != null) {
				for (TemplateToChange ttc : ttcs) {
					if ((ttc.getGroupId() == groupId)&&(ttc.getTemplateRef().equalsIgnoreCase(templateRef))) {return null;}
				}
			}
			TemplateToChange ttc = new TemplateToChange(groupId, templateRef);
			ttcs.add(ttc);
			return ttc;
		}
	}
	
	private final static class StructureToChange {
		
		private DDMStructure structure;
		private List<StructureFieldInfo> fields;
		private List<String> datePaths;
		
		public StructureToChange(DDMStructure structure, List<StructureFieldInfo> fields, List<String> datePaths) {
			this.structure = structure;
			this.fields = fields;
			this.datePaths = datePaths;
		}
		
		public DDMStructure getStructure() {return structure;}
		public List<StructureFieldInfo> getFields() {return fields;}
		public List<String> getDatePaths() {return datePaths;}
		
	}
	
}

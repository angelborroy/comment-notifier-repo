package es.keensoft.alfresco.behaviour;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.model.ContentModel;
import org.alfresco.model.ForumModel;
import org.alfresco.repo.action.executer.MailActionExecuter;
import org.alfresco.repo.admin.SysAdminParams;
import org.alfresco.repo.content.ContentServicePolicies;
import org.alfresco.repo.policy.Behaviour;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.repo.site.SiteModel;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ActionService;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.repository.TemplateService;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.cmr.security.PersonService.PersonInfo;
import org.alfresco.service.cmr.site.SiteInfo;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.util.UrlUtil;

public class CommentNotifierBehaviour implements ContentServicePolicies.OnContentUpdatePolicy {

	private PolicyComponent policyComponent;
    private NodeService nodeService;
    private ContentService contentService;
    private ActionService actionService;
    private SiteService siteService;
    private AuthorityService authorityService;
    private PersonService personService;
    private SearchService searchService;
    private TemplateService templateService;
    private SysAdminParams sysAdminParams;
    
    public void init() {
        policyComponent.bindClassBehaviour(ContentServicePolicies.OnContentUpdatePolicy.QNAME, ForumModel.TYPE_POST,
        		new JavaBehaviour(this, "onContentUpdate", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));
    }
    
	@Override
	public void onContentUpdate(NodeRef nodeRef, boolean newContent) {
		
		if (newContent && nodeService.exists(nodeRef)) {
			
			SiteInfo siteInfo = siteService.getSite(nodeRef);
			if (siteInfo != null) {
				
				ContentReader contentReader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				contentReader.getContent(baos);
				String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
				
				String creatorUserId = nodeService.getProperty(nodeRef, ContentModel.PROP_CREATOR).toString();
				PersonInfo creatorInfo = personService.getPerson(personService.getPerson(creatorUserId));
				
				NodeRef docNodeRef = getDocNodeRef(nodeRef);
				String docUrl = UrlUtil.getShareUrl(sysAdminParams) + 
						"/page/site/" + siteInfo.getShortName() + 
						"/document-details?nodeRef=workspace://SpacesStore/" + docNodeRef.getId();
				
				String siteRoleGroupManager = siteService.getSiteRoleGroup(siteInfo.getShortName(), SiteModel.SITE_MANAGER);
				for (String email : getUserEmailFromAuthority(siteRoleGroupManager)) {
					sendNotification(nodeService.getProperty(docNodeRef, ContentModel.PROP_NAME).toString(), docUrl, content, email, creatorInfo, siteInfo);
				}
			}
			
		}
	}
	
	private NodeRef getDocNodeRef(NodeRef commentNodeRef) {
	    NodeRef discussionNodeRef = nodeService.getPrimaryParent(nodeService.getPrimaryParent(commentNodeRef).getParentRef()).getParentRef();
	    return nodeService.getPrimaryParent(discussionNodeRef).getParentRef();
	}

	public void setNodeService(NodeService nodeService) {
		this.nodeService = nodeService;
	}

	public void setPolicyComponent(PolicyComponent policyComponent) {
		this.policyComponent = policyComponent;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}
	
	private List<String> getUserEmailFromAuthority(String authority) {
		List<String> userEmails = null;
		Set<String> usernames = authorityService.getContainedAuthorities(AuthorityType.USER, authority, false);
		if (usernames != null) {
			userEmails = new ArrayList<String>();
			NodeRef user;
			for(String username : usernames) {
				user = personService.getPersonOrNull(username);
				if(user != null) {
					userEmails.add(nodeService.getProperty(user, ContentModel.PROP_EMAIL).toString());
				}
			}
		}
		return userEmails;
	}
	
	
	private void sendNotification(String fileName, String fileUrl, String comment, String recipient, PersonInfo creator, SiteInfo site) {

		Action mailAction = actionService.createAction(MailActionExecuter.NAME);
		
		mailAction.setParameterValue(MailActionExecuter.PARAM_TO, recipient);
		mailAction.setParameterValue(MailActionExecuter.PARAM_SUBJECT, "New comment for document " + fileName);        
		
		Map<String, Serializable> templateArgs = new HashMap<String, Serializable>();
		templateArgs.put("comment", comment.replaceAll("\\<.*?\\>", ""));
		templateArgs.put("commentCreator", creator.getFirstName() + " " + (creator.getLastName() == null ? "" : creator.getLastName() + " "));
		templateArgs.put("documentName", fileName);
		templateArgs.put("siteName", site.getTitle());
		templateArgs.put("shareUrl", UrlUtil.getShareUrl(sysAdminParams));
		templateArgs.put("documentShareUrl", fileUrl);
		Map<String, Serializable> templateModel = new HashMap<String, Serializable>();
		templateModel.put("args",(Serializable) templateArgs);
		mailAction.setParameterValue(MailActionExecuter.PARAM_TEMPLATE_MODEL, (Serializable) templateModel);
		
		String emailMsg = templateService.processTemplate("freemarker", getTemplate().toString(), templateModel);
		mailAction.setParameterValue(MailActionExecuter.PARAM_TEXT, emailMsg);
		
		actionService.executeAction(mailAction, null);
	}
	
	private NodeRef getTemplate() {
		String templatePATH = "PATH:\"/app:company_home/app:dictionary/app:email_templates/app:notify_email_templates/cm:template_comments.html.ftl\"";
		ResultSet resultSet = searchService.query(new StoreRef(StoreRef.PROTOCOL_WORKSPACE, "SpacesStore"), SearchService.LANGUAGE_LUCENE, templatePATH);
		if (resultSet.length()==0) {
			return null;
		}
		return resultSet.getNodeRef(0);
	}
	
	public void setSiteService(SiteService siteService) {
		this.siteService = siteService;
	}

	public void setPersonService(PersonService personService) {
		this.personService = personService;
	}

	public void setAuthorityService(AuthorityService authorityService) {
		this.authorityService = authorityService;
	}

	public void setActionService(ActionService actionService) {
		this.actionService = actionService;
	}

	public void setSearchService(SearchService searchService) {
		this.searchService = searchService;
	}

	public void setTemplateService(TemplateService templateService) {
		this.templateService = templateService;
	}

	public void setSysAdminParams(SysAdminParams sysAdminParams) {
		this.sysAdminParams = sysAdminParams;
	}

}

package net.sourceforge.subsonic.controller;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import net.sourceforge.subsonic.command.TagSettingsCommand;

import org.springframework.web.servlet.mvc.SimpleFormController;

import com.github.hakko.musiccabinet.domain.model.aggr.TagOccurrence;
import com.github.hakko.musiccabinet.service.TagService;

/**
 * Controller for tag configuration.
 *
 * @author hakko / MusicCabinet
 */
public class TagSettingsController extends SimpleFormController {

    private TagService tagService;

    protected Object formBackingObject(HttpServletRequest request) throws Exception {
        TagSettingsCommand command = new TagSettingsCommand();
        
        command.setAvailableTags(tagService.getAvailableTags());

        List<String> topTags = new ArrayList<String>();
        for (TagOccurrence to : command.getAvailableTags()) {
        	if (to.isUse()) {
        		topTags.add(to.getTag());
        	}
        }
        command.setTopTags(topTags);
        
        return command;
    }

    protected void doSubmitAction(Object comm) throws Exception {
        TagSettingsCommand command = (TagSettingsCommand) comm;
        
        tagService.setTopTags(command.getTopTags());
    }
    
    // Spring setters

	public void setTagService(TagService tagService) {
		this.tagService = tagService;
	}

}
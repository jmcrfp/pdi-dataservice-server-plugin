package org.pentaho.di.repository.model;

import java.util.List;

import org.pentaho.di.repository.ObjectRecipient;


public interface ObjectAcl {

	public List<ObjectAce> getAces();
	public ObjectRecipient getOwner();
	public boolean isEntriesInheriting();
	public void setAces(List<ObjectAce> aces);
	public void setOwner(ObjectRecipient owner);
	public void setEntriesInheriting(boolean entriesInheriting);
}

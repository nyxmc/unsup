package com.unascribed.sup.json;

public class OrderedVersion {
	
	public String name;
	public int code;
	
	public OrderedVersion() {}
	
	public OrderedVersion(String name, int code) {
		this.name = name;
		this.code = code;
	}
	
	@Override
	public String toString() {
		return name+" ("+code+")";
	}
	
}